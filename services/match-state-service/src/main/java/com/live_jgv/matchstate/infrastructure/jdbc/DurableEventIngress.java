package com.live_jgv.matchstate.infrastructure.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.matchstate.application.service.MatchStateService;
import com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.application.port.in.IncomingEventHandler;
import com.live_jgv.matchstate.infrastructure.kafka.MatchEventMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Guarda atómicamente el evento aceptado con outbox o lo conserva en cuarentena. */
public final class DurableEventIngress implements IncomingEventHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DurableEventIngress.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final JdbcMatchStateStore store;
    private final ProcessAndPublishMatchEvent publisherLane;
    private final java.util.concurrent.ConcurrentMap<String,Object> custodyLanes = new java.util.concurrent.ConcurrentHashMap<>();

    public DurableEventIngress(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper,
                               JdbcMatchStateStore store, ProcessAndPublishMatchEvent publisherLane) {
        this.jdbc = jdbc; this.tx = tx; this.mapper = mapper; this.store = store; this.publisherLane = publisherLane;
    }

    @Override
    public void handle(InputRecord input) {
        String key = input.key() == null ? "null" : Base64.getEncoder().encodeToString(input.key());
        final String matchToDrain;
        synchronized (custodyLanes.computeIfAbsent(key, ignored -> new Object())) {
            matchToDrain = accept(input);
        }
        // Libera el monitor local antes de esperar a RabbitMQ.
        if (matchToDrain != null) publisherLane.recoverPendingMatch(matchToDrain);
    }

    private String accept(InputRecord input) {
        MatchEvent parsed = null;
        try {
            var wire = mapper.readValue(utf8(input.value()), MatchEventMessage.class);
            if (wire == null) throw new IllegalArgumentException("Null event");
            var candidate = wire.toDomain();
            if (!candidate.matchId().equals(utf8(input.key()))) throw new IllegalArgumentException("Key mismatch");
            parsed = candidate;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException | java.time.DateTimeException failure) {
            // Este bloque captura validación; los fallos de base de datos y publicación se propagan.
        }
        final MatchEvent event = parsed;
        String matchToDrain = tx.execute(status -> {
            var prior = jdbc.queryForList("SELECT * FROM match_state.input_receipt WHERE source_id=? AND topic=? AND partition_id=? AND record_offset=? FOR UPDATE",
                    input.source(), input.topic(), input.partition(), input.offset());
            if (!prior.isEmpty()) {
                var row = prior.get(0);
                if (!Arrays.equals((byte[]) row.get("raw_key"), input.key()) || !Arrays.equals((byte[]) row.get("raw_value"), input.value())) {
                    throw new IllegalStateException("Input coordinate reused with different bytes; review source epoch");
                }
                jdbc.update("UPDATE match_state.input_receipt SET delivery_attempts=delivery_attempts+1,last_seen=CURRENT_TIMESTAMP WHERE receipt_id=?", row.get("receipt_id"));
                return "APPLIED".equals(row.get("outcome")) ? (String) row.get("match_id") : null;
            }
            if (event == null) {
                receipt(input, null, "QUARANTINED", quarantineMalformed(input)); return null;
            }
            registerDemoRound(event);
            var loaded = store.load(event.matchId()); // Un historial corrupto es un fallo de almacenamiento, no un rechazo del mensaje.
            var accepted = loaded.map(l -> l.state().processedEvents().get(event.eventId())).orElse(null);
            var identity = identity(accepted == null ? event : accepted, accepted != null);
            if (!decode((String) identity.get("canonical_payload"), MatchEvent.class).equals(event)) {
                receipt(input, event, "QUARANTINED", quarantine(event, "ID_CONFLICT", false)); return null;
            }
            String identityState = (String) identity.get("status");
            if ("APPLIED".equals(identityState)) {
                if (accepted == null || !accepted.equals(event)) throw new IllegalStateException("Identity/ledger mismatch");
                receipt(input, event, "APPLIED", null); return event.matchId();
            }
            if ("DEFERRED".equals(identityState) || "REJECTED".equals(identityState)) {
                receipt(input, event, "DEFERRED".equals(identityState) ? "DEFERRED" : "QUARANTINED", (UUID) identity.get("case_id")); return null;
            }
            String reason = loaded.isEmpty() ? "UNREGISTERED_MATCH" : rejection(loaded.get().state(), event);
            if (reason != null) {
                boolean deferred = reason.equals("WAITING_REFERENCE") || reason.equals("UNREGISTERED_MATCH");
                UUID caseId = quarantine(event, reason, deferred);
                jdbc.update("UPDATE match_state.event_identity SET status=?,case_id=? WHERE match_id=? AND event_id=?",
                        deferred ? "DEFERRED" : "REJECTED", caseId, event.matchId(), event.eventId());
                receipt(input, event, deferred ? "DEFERRED" : "QUARANTINED", caseId); return null;
            }
            new MatchStateService(store).process(event); // La misma transacción incluye revisión CAS, historial y outbox.
            jdbc.update("UPDATE match_state.event_identity SET status='APPLIED' WHERE match_id=? AND event_id=?", event.matchId(), event.eventId());
            receipt(input, event, "APPLIED", null); return event.matchId();
        });
        log.info("Input custody committed; topic={}, partition={}, offset={}, outcome={}", input.topic(), input.partition(), input.offset(), matchToDrain == null ? "PARKED" : "APPLIED");
        return matchToDrain;
    }

    private void registerDemoRound(MatchEvent event) {
        String id = event.matchId();
        if (event.minute() != 0 || event.eventType() != com.live_jgv.matchstate.domain.model.EventType.MATCH_CONTINUES
                || !id.matches("demo-col-v4-00[1-6][.][a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) return;
        String template = id.substring(0, id.lastIndexOf('.'));
        jdbc.update("""
                INSERT INTO match_state.match_catalog(match_id,home_team_id,away_team_id)
                SELECT ?, home_team_id, away_team_id FROM match_state.match_catalog WHERE match_id=?
                ON CONFLICT DO NOTHING
                """, id, template);
    }

    private Map<String, Object> identity(MatchEvent event, boolean applied) {
        jdbc.update("INSERT INTO match_state.event_identity(match_id,event_id,canonical_payload,status) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                event.matchId(), event.eventId(), encode(event), applied ? "APPLIED" : "NEW");
        return jdbc.queryForMap("SELECT * FROM match_state.event_identity WHERE match_id=? AND event_id=? FOR UPDATE", event.matchId(), event.eventId());
    }

    private String rejection(MatchState state, MatchEvent event) {
        try { MatchStateService.requirePublishableReference(event); state.apply(event); return null; }
        catch (InvalidMatchEventException failure) { return failure.reason().name(); }
    }

    private UUID quarantine(MatchEvent event, String reason, boolean deferred) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO match_state.quarantine_case(case_id,reason_code,review_state,match_id,event_id,reference_event_id,canonical_payload) VALUES (?,?,?,?,?,?,?)",
                id, reason, deferred ? "DEFERRED" : "QUARANTINED", event == null ? null : event.matchId(),
                event == null ? null : event.eventId(), event == null ? null : event.referenceEventId(), event == null ? null : encode(event));
        return id;
    }

    private UUID quarantineMalformed(InputRecord input) {
        String match = null, event = null, reference = null;
        try {
            var tree = mapper.readTree(utf8(input.value()));
            if (tree != null && tree.isObject()) {
                match = textual(tree.get("match_id")); event = textual(tree.get("event_id")); reference = textual(tree.get("reference_event_id"));
                if (reference == null) reference = textual(tree.get("annuls_event_id"));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ignored) {
            // Un JSON inválido conserva sus bytes; no se confía en su identidad extraída.
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO match_state.quarantine_case(case_id,reason_code,review_state,match_id,event_id,reference_event_id) VALUES (?,'MALFORMED','QUARANTINED',?,?,?)", id, match, event, reference);
        return id;
    }

    private String textual(com.fasterxml.jackson.databind.JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private void receipt(InputRecord input, MatchEvent event, String outcome, UUID caseId) {
        String matchId = event == null ? null : event.matchId(), eventId = event == null ? null : event.eventId();
        if (event == null && caseId != null) {
            var identity = jdbc.queryForMap("SELECT match_id,event_id FROM match_state.quarantine_case WHERE case_id=?", caseId);
            matchId = (String) identity.get("match_id"); eventId = (String) identity.get("event_id");
        }
        jdbc.update("INSERT INTO match_state.input_receipt(receipt_id,source_id,topic,partition_id,record_offset,record_timestamp,raw_key,raw_value,original_headers,outcome,match_id,event_id,case_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), input.source(), input.topic(), input.partition(), input.offset(), input.timestamp(), input.key(), input.value(), input.headers(), outcome,
                matchId, eventId, caseId);
    }

    /** Vista de revisión enlazada con los originales mediante input_receipt.case_id. */
    public List<Map<String, Object>> cases() {
        return jdbc.queryForList("SELECT * FROM match_state.quarantine_case ORDER BY first_seen,case_id");
    }

    /** Exige aprobación sobre una revisión; la llegada de un gol no la ejecuta automáticamente. */
    public String replay(ReplayRequest request) {
        String[] matchToDrain = new String[1];
        String result = tx.execute(status -> {
            var prior = jdbc.queryForList("SELECT * FROM match_state.replay_attempt WHERE replay_id=?", request.replayId());
            if (!prior.isEmpty()) {
                var row = prior.get(0);
                if (!row.get("case_id").equals(request.caseId()) || !row.get("actor").equals(request.actor())
                        || !row.get("reason").equals(request.reason()) || ((Number) row.get("expected_revision")).longValue() != request.expectedRevision()) {
                    throw new IllegalArgumentException("Replay ID already belongs to another request");
                }
                if ("APPLIED".equals(row.get("result")) || "ALREADY_APPLIED".equals(row.get("result"))) {
                    matchToDrain[0] = jdbc.queryForObject("SELECT match_id FROM match_state.quarantine_case WHERE case_id=?", String.class, request.caseId());
                }
                return (String) row.get("result");
            }
            var cases = jdbc.queryForList("SELECT * FROM match_state.quarantine_case WHERE case_id=? FOR UPDATE", request.caseId());
            if (cases.isEmpty()) throw new IllegalArgumentException("Unknown quarantine case");
            var q = cases.get(0);
            String payload = (String) q.get("canonical_payload");
            String outcome = "REJECTED", detail = "Not replayable; preserve original and submit correction with new ID";
            if (payload != null && ("DEFERRED".equals(q.get("review_state")) || "RESOLVED".equals(q.get("review_state")))) {
                var event = decode(payload, MatchEvent.class);
                var locked = identity(event, false);
                if (!decode((String) locked.get("canonical_payload"), MatchEvent.class).equals(event)) throw new IllegalStateException("Case/identity mismatch");
                var loaded = store.load(event.matchId());
                if ("APPLIED".equals(locked.get("status"))) {
                    if (loaded.isEmpty() || !event.equals(loaded.get().state().processedEvents().get(event.eventId()))) throw new IllegalStateException("Replay/ledger mismatch");
                    outcome = "ALREADY_APPLIED"; detail = "Already accepted; no new effect"; matchToDrain[0] = event.matchId();
                } else if (loaded.isEmpty() || loaded.get().revision() != request.expectedRevision()) {
                    outcome = "REVIEW_REQUIRED"; detail = "Catalog or revision changed; new approval required";
                } else {
                    String rejected = rejection(loaded.get().state(), event);
                    if (rejected != null) { outcome = "REJECTED"; detail = rejected; }
                    else {
                        try {
                            new MatchStateService(store).processAtRevision(event, request.expectedRevision());
                            jdbc.update("UPDATE match_state.event_identity SET status='APPLIED' WHERE match_id=? AND event_id=?", event.matchId(), event.eventId());
                            jdbc.update("UPDATE match_state.quarantine_case SET review_state='RESOLVED',resolved_at=CURRENT_TIMESTAMP WHERE case_id=?", request.caseId());
                            outcome = "APPLIED"; detail = "Accepted with outbox; delivery tracked separately"; matchToDrain[0] = event.matchId();
                        } catch (ConcurrentModificationException conflict) {
                            outcome = "REVIEW_REQUIRED"; detail = "Concurrent revision change; new approval required";
                        }
                    }
                }
            }
            jdbc.update("INSERT INTO match_state.replay_attempt(replay_id,case_id,actor,reason,expected_revision,result,detail) VALUES (?,?,?,?,?,?,?)",
                    request.replayId(), request.caseId(), request.actor(), request.reason(), request.expectedRevision(), outcome, detail);
            return outcome;
        });
        log.info("Replay application recorded; replayId={}, caseId={}, result={}", request.replayId(), request.caseId(), result);
        if (matchToDrain[0] != null) publisherLane.recoverPendingMatch(matchToDrain[0]);
        return result;
    }

    private String utf8(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Null transport value");
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException failure) { throw new IllegalArgumentException("Invalid UTF-8", failure); }
    }
    private String encode(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException("Cannot serialize ingress metadata", failure); } }
    private <T> T decode(String value, Class<T> type) { try { return mapper.readValue(value, type); } catch (Exception failure) { throw new IllegalStateException("Corrupt durable ingress data", failure); } }
}
