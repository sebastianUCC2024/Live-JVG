package com.live_jgv.notification.infrastructure.jdbc;

import com.live_jgv.notification.application.NotificationStore;
import com.live_jgv.notification.domain.*;
import com.live_jgv.notification.infrastructure.json.ScoreUpdateCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Guarda inbox y alerta con REQUIRES_NEW; el commit ocurre antes del ACK del listener. */
public final class JdbcNotificationStore implements NotificationStore {
    private static final String SOURCE = "match-state-service";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ScoreUpdateCodec codec;
    public JdbcNotificationStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ScoreUpdateCodec codec) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.codec = codec;
        // Usa una transacción propia para confirmar datos antes del ACK.
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public Result acceptLiveAlert(LiveAlertV1 wire) {
        String title=switch(wire.eventType()) { case GOAL -> "Gol"; case VAR_CHECK -> "Revisi\u00f3n VAR"; case GOAL_ANNULLED -> "Gol anulado"; default -> throw new InvalidUpdateException("Unsupported alert"); };
        return accept(wire.snapshot(),Optional.of(new LiveAlert(wire.alertId(),wire.snapshot(),wire.timestamp(),
                new LiveAlert.Content("es",title,wire.message()))),wire);
    }
    @Override public Result accept(ScoreUpdate update, Optional<LiveAlert> alert) { return accept(update,alert,null); }
    private Result accept(ScoreUpdate update, Optional<LiveAlert> alert, LiveAlertV1 wire) {
        String payload = codec.encode(update);
        return tx.execute(status -> {
            if(wire!=null) {
                String encoded=new com.live_jgv.notification.infrastructure.json.LiveAlertCodec().encode(wire);
                int claimed=jdbc.update("INSERT INTO notification.live_alert_inbox(alert_id,match_id,source_event_id,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING",wire.alertId(),wire.matchId(),wire.sourceEventId(),encoded);
                if(claimed==0) {
                    var previous=jdbc.queryForList("SELECT payload::text FROM notification.live_alert_inbox WHERE alert_id=? OR (match_id=? AND source_event_id=?)",wire.alertId(),wire.matchId(),wire.sourceEventId());
                    if(previous.size()!=1 || !new com.live_jgv.notification.infrastructure.json.LiveAlertCodec().decode(previous.get(0).get("payload").toString().getBytes(StandardCharsets.UTF_8)).equals(wire)) throw new IdentityConflictException();
                    return Result.DUPLICATE;
                }
            }
            int inserted = jdbc.update("""
                INSERT INTO notification.inbox(source,match_id,event_id,payload,outcome)
                VALUES (?,?,?,?::jsonb,?) ON CONFLICT (source,match_id,event_id) DO NOTHING
                """, SOURCE, update.matchId(), update.eventId(), payload, alert.isPresent() ? "ALERT" : "SUPPRESSED");
            if (inserted == 0) {
                if(wire!=null) throw new IdentityConflictException();
                String prior = jdbc.queryForObject("SELECT payload::text FROM notification.inbox WHERE source=? AND match_id=? AND event_id=?",
                        String.class, SOURCE, update.matchId(), update.eventId());
                if (!codec.decode(prior.getBytes(StandardCharsets.UTF_8)).equals(update)) throw new IdentityConflictException();
                return Result.DUPLICATE;
            }
            if (alert.isEmpty()) return Result.SUPPRESSED;
            var value = alert.orElseThrow();
            if (!value.source().equals(update)) throw new IllegalArgumentException("Alert source mismatch");
            // Mantiene el bloqueo hasta COMMIT para ordenar las alertas confirmadas.
            // Evita que el cursor salte una alerta que todavía no se ha confirmado.
            Long sequence = jdbc.queryForObject("UPDATE notification.cursor_state SET last_sequence=last_sequence+1 WHERE singleton=TRUE RETURNING last_sequence", Long.class);
            if (sequence == null) throw new IllegalStateException("Missing notification cursor state");
            jdbc.update("""
                INSERT INTO notification.alerts(sequence,alert_id,source,match_id,event_id,created_at,title,message)
                VALUES (?,?,?,?,?,?,?,?)
                """, sequence, value.alertId(), SOURCE, update.matchId(), update.eventId(),
                    value.createdAt().toString(), value.content().title(), value.content().message());
            return Result.CREATED;
        });
    }
    record StoredAlert(long sequence, LiveAlert alert) { }
    @Override public AlertPage query(String matchId, String after, int limit) {
        if (matchId == null || matchId.isBlank() || matchId.length() > 1024 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid alert query");
        var cursor = after == null ? null : AlertCursor.decode(after, matchId);
        var state = jdbc.queryForMap("SELECT epoch,last_sequence FROM notification.cursor_state WHERE singleton=TRUE");
        UUID epoch = UUID.fromString(state.get("epoch").toString());
        long upper = ((Number) state.get("last_sequence")).longValue();
        if (cursor != null && (!epoch.equals(cursor.epoch()) || cursor.sequence() > upper)) throw new AlertCursor.StaleCursorException();
        boolean initial = cursor == null;
        long lower = initial ? 0 : cursor.sequence();
        List<StoredAlert> rows = jdbc.query("""
                SELECT a.sequence,a.alert_id,a.created_at,a.title,a.message,i.payload::text
                FROM notification.alerts a JOIN notification.inbox i
                  ON (i.source,i.match_id,i.event_id)=(a.source,a.match_id,a.event_id)
                WHERE a.match_id=? AND a.sequence>? AND a.sequence<=?
                ORDER BY a.sequence
                """ + (initial ? " DESC" : " ASC") + " LIMIT ?", (rs, row) -> {
            var update = codec.decode(rs.getString("payload").getBytes(StandardCharsets.UTF_8));
            return new StoredAlert(rs.getLong("sequence"), new LiveAlert(UUID.fromString(rs.getString("alert_id")),
                    update, Instant.parse(rs.getString("created_at")), new LiveAlert.Content("es", rs.getString("title"), rs.getString("message"))));
        }, matchId, lower, upper, initial ? limit : limit + 1);
        boolean more = !initial && rows.size() > limit;
        var selected = new ArrayList<>(rows.subList(0, Math.min(rows.size(), limit)));
        if (initial) Collections.reverse(selected);
        long checkpoint = more ? selected.get(selected.size() - 1).sequence() : upper;
        return new AlertPage(selected.stream().map(StoredAlert::alert).toList(), new AlertCursor(epoch, matchId, checkpoint).encode(), more);
    }
}
