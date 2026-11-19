package com.live_jgv.archiver.infrastructure;

import com.live_jgv.archiver.application.ArchiveStore;
import com.live_jgv.archiver.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public final class JdbcArchiveStore implements ArchiveStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcArchiveStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.transactions = transactions;
    }
    @Override public ArchiveOutcome save(InputRecord record, DecodedInput input) {
        return Objects.requireNonNull(transactions.execute(status -> saveInTransaction(record, input)));
    }
    private ArchiveOutcome saveInTransaction(InputRecord r, DecodedInput input) {
        int insertedReceipt = jdbc.update("""
                INSERT INTO archiver.input_receipt(source_id,topic,partition_id,record_offset,broker_timestamp,
                    original_key,original_payload,original_headers,outcome)
                VALUES (?,?,?,?,?,?,?,?,'PENDING') ON CONFLICT DO NOTHING
                """, r.sourceId(), r.topic(), r.partition(), r.offset(), r.brokerTimestamp(), r.key(), r.value(), r.headers());
        if (insertedReceipt == 0) {
            var prior = jdbc.queryForMap("""
                    SELECT original_key,original_payload,original_headers,broker_timestamp,outcome
                    FROM archiver.input_receipt
                    WHERE source_id=? AND topic=? AND partition_id=? AND record_offset=? FOR UPDATE
                    """, position(r));
            if (!Arrays.equals((byte[]) prior.get("original_key"), r.key())
                    || !Arrays.equals((byte[]) prior.get("original_payload"), r.value())
                    || !r.headers().equals(prior.get("original_headers"))
                    || ((Number) prior.get("broker_timestamp")).longValue() != r.brokerTimestamp()) {
                // No sobrescribe evidencia si se reutiliza la identidad de un log anterior.
                throw new IllegalStateException("Source position reused with different content; change source identity after review");
            }
            return ArchiveOutcome.valueOf((String) prior.get("outcome"));
        }
        ArchiveOutcome outcome;
        String reason = null, eventId = null;
        if (input.reason() != null) {
            outcome = ArchiveOutcome.QUARANTINED; reason = input.reason().name();
        } else {
            var event = input.event();
            int inserted = jdbc.update("""
                    INSERT INTO archiver.archived_event(event_id,match_id,event_type,team_id,minute,event_timestamp,
                        reference_event_id,original_payload,canonical_payload) VALUES (?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (match_id,event_id) DO NOTHING
                    """, event.eventId(), event.matchId(), event.eventType().name(), event.teamId(), event.minute(),
                    event.timestamp().toString(), event.referenceEventId(), r.value(), input.canonicalPayload());
            eventId = event.eventId();
            if (inserted == 1) outcome = ArchiveOutcome.STORED;
            else {
                String previous = jdbc.queryForObject("SELECT canonical_payload FROM archiver.archived_event WHERE match_id=? AND event_id=?",
                        String.class, event.matchId(), eventId);
                outcome = input.canonicalPayload().equals(previous) ? ArchiveOutcome.DUPLICATE : ArchiveOutcome.IDENTITY_CONFLICT;
                if (outcome == ArchiveOutcome.IDENTITY_CONFLICT) reason = "EVENT_ID_REUSED";
            }
        }
        int finalized = jdbc.update("""
                UPDATE archiver.input_receipt SET outcome=?,match_id=?,event_id=?,reason_code=?
                WHERE source_id=? AND topic=? AND partition_id=? AND record_offset=?
                """, outcome.name(), input.event()==null?null:input.event().matchId(), eventId, reason, r.sourceId(), r.topic(), r.partition(), r.offset());
        if (finalized != 1) throw new IllegalStateException("Archive receipt was not finalized");
        return outcome;
    }
    private static Object[] position(InputRecord r) { return new Object[]{r.sourceId(), r.topic(), r.partition(), r.offset()}; }

    @Override public HistoryPage find(String match, int page, int size) {
        // El total y las filas comparten un snapshot repeatable-read.
        var read = new TransactionTemplate(transactions.getTransactionManager());
        read.setReadOnly(true);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(read.execute(status -> {
            Long count = jdbc.queryForObject("SELECT count(*) FROM archiver.archived_event WHERE match_id=?", Long.class, match);
            long total = Objects.requireNonNull(count);
            var rows = jdbc.query("""
                    SELECT seq,event_id,match_id,event_type,team_id,minute,event_timestamp,reference_event_id,received_at
                    FROM archiver.archived_event WHERE match_id=? ORDER BY seq ASC LIMIT ? OFFSET ?
                    """, JdbcArchiveStore::entry, match, size, Math.multiplyExact((long) page, size));
            return new HistoryPage(rows, page, size, total, total / size + (total % size == 0 ? 0 : 1));
        }));
    }
    private static HistoryPage.Entry entry(ResultSet rs, int row) throws SQLException {
        return new HistoryPage.Entry(rs.getLong("seq"), rs.getString("event_id"), rs.getString("match_id"),
                rs.getString("event_type"), rs.getString("team_id"), rs.getInt("minute"),
                Instant.parse(rs.getString("event_timestamp")), rs.getString("reference_event_id"),
                rs.getTimestamp("received_at").toInstant());
    }
}
