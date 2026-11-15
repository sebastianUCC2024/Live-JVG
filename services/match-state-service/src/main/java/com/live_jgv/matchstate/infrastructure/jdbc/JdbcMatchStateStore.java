package com.live_jgv.matchstate.infrastructure.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.domain.port.MatchStateRepository;
import com.live_jgv.matchstate.domain.port.ScoreUpdateOutbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** One PostgreSQL transaction commits revision, accepted event and immutable outbox. */
public final class JdbcMatchStateStore implements MatchStateRepository, ScoreUpdateOutbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final String exchange;
    private final String routingPattern;
    private final ThreadLocal<JdbcTemplate> publicationSession = new ThreadLocal<>();

    public JdbcMatchStateStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
                               String exchange, String routingPattern) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        if (exchange == null || exchange.isBlank() || routingPattern == null || !routingPattern.contains("{matchId}")) {
            throw new IllegalArgumentException("Invalid durable outbox destination");
        }
        this.exchange = exchange;
        this.routingPattern = routingPattern;
    }

    public void register(MatchState initial) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO match_state.match_catalog(match_id,home_team_id,away_team_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    initial.matchId(), initial.homeTeamId(), initial.awayTeamId());
            var existing = jdbc.queryForMap("SELECT home_team_id,away_team_id FROM match_state.match_catalog WHERE match_id=?", initial.matchId());
            if (!existing.get("home_team_id").equals(initial.homeTeamId()) || !existing.get("away_team_id").equals(initial.awayTeamId())) {
                throw new IllegalStateException("Configured teams conflict with durable catalog");
            }
        });
    }

    public List<String> matchIds() {
        return jdbc.queryForList("SELECT match_id FROM match_state.match_catalog ORDER BY match_id", String.class);
    }

    @Override
    public Optional<LoadedMatch> load(String matchId) {
        // A single statement gives catalog + complete ordered history the same MVCC snapshot.
        return transactions.execute(status -> jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                SELECT c.home_team_id,c.away_team_id,c.revision,c.rules_version,
                       e.event_id,e.application_seq,e.event_payload
                  FROM match_state.match_catalog c LEFT JOIN match_state.accepted_event e ON e.match_id=c.match_id
                 WHERE c.match_id=? ORDER BY e.application_seq
                """);
            statement.setString(1, matchId);
            statement.setFetchSize(256); // PostgreSQL cursor inside this DB-only transaction.
            return statement;
        }, rs -> {
            if (!rs.next()) return Optional.empty();
            var replay = MatchState.rebuilder(matchId, rs.getString("home_team_id"), rs.getString("away_team_id"));
            long revision = rs.getLong("revision"), sequence = 0;
            if (rs.getInt("rules_version") != 1) throw new IllegalStateException("Unsupported stored rules version");
            do {
                if (rs.getString("event_id") != null) {
                    if (rs.getLong("application_seq") != ++sequence) throw new IllegalStateException("Non-contiguous accepted history");
                    var event = decode(rs.getString("event_payload"), MatchEvent.class);
                    if (!event.eventId().equals(rs.getString("event_id"))) throw new IllegalStateException("Stored event identity mismatch");
                    replay.accept(event);
                }
            } while (rs.next());
            if (sequence != revision) throw new IllegalStateException("Durable revision/history mismatch");
            return Optional.of(new LoadedMatch(replay.finish(), revision));
        }));
    }

    @Override
    public boolean compareAndSet(LoadedMatch expected, MatchState updated, ScoreUpdate update) {
        var before = expected.state();
        var event = updated.processedEvents().get(update.eventId());
        if (!before.matchId().equals(updated.matchId()) || !updated.matchId().equals(update.matchId())
                || !before.homeTeamId().equals(updated.homeTeamId()) || !before.awayTeamId().equals(updated.awayTeamId())
                || updated.processedEvents().size() != expected.revision() + 1 || event == null
                || before.processedEvents().containsKey(event.eventId())
                || !updated.processedEvents().entrySet().containsAll(before.processedEvents().entrySet())
                || !update.eventType().equals(event.eventType()) || !Objects.equals(update.referenceEventId(), event.referenceEventId())
                || update.homeScore() != updated.score().home() || update.awayScore() != updated.score().away()
                || update.varStatus() != updated.varStatus()) {
            throw new IllegalArgumentException("Inconsistent state/history/outbox transition");
        }
        String eventJson = encode(event), outputJson = encode(update);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int changed = jdbc.update("UPDATE match_state.match_catalog SET revision=revision+1 WHERE match_id=? AND revision=?",
                    updated.matchId(), expected.revision());
            if (changed == 0) return false;
            long next = expected.revision() + 1;
            jdbc.update("INSERT INTO match_state.accepted_event(match_id,event_id,application_seq,event_payload) VALUES (?,?,?,?)",
                    updated.matchId(), event.eventId(), next, eventJson);
            jdbc.update("INSERT INTO match_state.score_outbox(match_id,event_id,application_seq,payload,exchange_name,routing_key) VALUES (?,?,?,?,?,?)",
                    updated.matchId(), event.eventId(), next, outputJson, exchange, routingPattern.replace("{matchId}", updated.matchId()));
            return true;
        }));
    }

    @Override
    public void withPublicationLock(String matchId, Runnable publication) {
        if (publicationSession.get() != null) throw new IllegalStateException("Nested publication session");
        new PostgresPublicationLock(Objects.requireNonNull(jdbc.getDataSource())).execute(matchId, session -> {
            publicationSession.set(session);
            try { publication.run(); }
            finally { publicationSession.remove(); }
        });
    }

    private JdbcTemplate publicationJdbc() {
        var session = publicationSession.get();
        return session == null ? jdbc : session;
    }

    @Override
    public List<ScoreUpdate> pendingForMatch(String matchId) {
        return publicationJdbc().query("SELECT payload FROM match_state.score_outbox WHERE match_id=? AND NOT published ORDER BY application_seq LIMIT 128",
                (rs, index) -> decode(rs.getString(1), ScoreUpdate.class), matchId);
    }

    @Override
    public Optional<StoredScoreUpdate> storedPublication(ScoreUpdate update) {
        var results = publicationJdbc().query("SELECT payload,exchange_name,routing_key FROM match_state.score_outbox WHERE match_id=? AND event_id=? AND NOT published",
                (rs, index) -> new StoredScoreUpdate(decode(rs.getString(1), ScoreUpdate.class), rs.getString(1), rs.getString(2), rs.getString(3)),
                update.matchId(), update.eventId());
        if (results.size() != 1 || !results.get(0).update().equals(update)) {
            throw new IllegalStateException("Pending durable publication mismatch");
        }
        return Optional.of(results.get(0));
    }

    @Override
    public void markPublished(ScoreUpdate update) {
        Runnable acknowledge = () -> {
            storedPublication(update); // validate the same logical payload before recording acknowledgement
            int count = publicationJdbc().update("UPDATE match_state.score_outbox SET published=TRUE WHERE match_id=? AND event_id=? AND NOT published",
                    update.matchId(), update.eventId());
            if (count != 1) throw new IllegalStateException("Publication acknowledgement conflict");
        };
        if (publicationSession.get() != null) acknowledge.run();
        else transactions.executeWithoutResult(status -> acknowledge.run());
    }

    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot encode durable record", failure); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("Cannot decode durable record", failure); }
    }
}
