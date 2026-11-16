package com.live_jgv.betting.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.betting.application.SuspensionStore;
import com.live_jgv.betting.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

public final class JdbcSuspensionStore implements SuspensionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public JdbcSuspensionStore(JdbcTemplate jdbc,TransactionTemplate tx,Clock clock) { this.jdbc=jdbc;this.tx=tx;this.clock=clock; }
    @Override public Outcome handle(InputRecord r,DecodedInput input) {
        return Objects.requireNonNull(tx.execute(status->{
            int receipt=jdbc.update("""
                    INSERT INTO betting_control.input_receipt(source_id,topic,partition_id,record_offset,broker_timestamp,
                        original_key,original_payload,original_headers,outcome) VALUES (?,?,?,?,?,?,?,?,'PENDING') ON CONFLICT DO NOTHING
                    """,r.sourceId(),r.topic(),r.partition(),r.offset(),r.brokerTimestamp(),r.key(),r.value(),r.headers());
            if(receipt==0) return existingReceipt(r);
            Outcome outcome;String reason=null;
            if(input.reason()!=null) { outcome=Outcome.QUARANTINED;reason=input.reason().name(); }
            else {
                var event=input.event();var before=locked(event.matchId());
                var prior=jdbc.queryForList("SELECT canonical_payload FROM betting_control.processed_event WHERE match_id=? AND event_id=?",event.matchId(),event.eventId());
                if(!prior.isEmpty()) {
                    if(input.canonicalPayload().equals(prior.get(0).get("canonical_payload"))) outcome=Outcome.DUPLICATE;
                    else {
                        outcome=Outcome.IDENTITY_CONFLICT;reason="EVENT_ID_REUSED";
                        persist(before,before.conflict(clock.instant()),event.eventId());
                    }
                } else {
                    var after=before.apply(event,clock.instant());
                    persistOne("""
                            INSERT INTO betting_control.processed_event(match_id,event_id,event_type,team_id,minute,event_timestamp,
                                reference_event_id,applied_version,canonical_payload,original_payload) VALUES (?,?,?,?,?,?,?,?,?,?)
                            """,event.matchId(),event.eventId(),event.eventType().name(),event.teamId(),event.minute(),event.timestamp().toString(),
                            event.referenceEventId(),after.version(),input.canonicalPayload(),r.value());
                    if(event.eventType()==SportEvent.EventType.VAR_CHECK || event.eventType()==SportEvent.EventType.GOAL) persistOne("""
                            INSERT INTO betting_control.pending_review(match_id,review_id,reference_event_id,opened_version) VALUES (?,?,?,?)
                            """,event.matchId(),event.eventId(),event.eventType()==SportEvent.EventType.GOAL?event.eventId():event.referenceEventId(),after.version());
                    persist(before,after,event.eventId());outcome=Outcome.APPLIED;
                }
            }
            int finalized=jdbc.update("""
                    UPDATE betting_control.input_receipt SET outcome=?,reason_code=?,match_id=?,event_id=?
                    WHERE source_id=? AND topic=? AND partition_id=? AND record_offset=?
                    """,outcome.name(),reason,input.event()==null?null:input.event().matchId(),input.event()==null?null:input.event().eventId(),r.sourceId(),r.topic(),r.partition(),r.offset());
            if(finalized!=1) throw new IllegalStateException("Input receipt not finalized");
            return outcome;
        }));
    }
    private Outcome existingReceipt(InputRecord r) {
        var prior=jdbc.queryForMap("""
                SELECT * FROM betting_control.input_receipt
                WHERE source_id=? AND topic=? AND partition_id=? AND record_offset=? FOR UPDATE
                """,r.sourceId(),r.topic(),r.partition(),r.offset());
        if(!Arrays.equals(r.key(),(byte[])prior.get("original_key")) || !Arrays.equals(r.value(),(byte[])prior.get("original_payload"))
                || !r.headers().equals(prior.get("original_headers")) || r.brokerTimestamp()!=((Number)prior.get("broker_timestamp")).longValue())
            throw new IllegalStateException("Source position reused; review source identity");
        return Outcome.valueOf((String)prior.get("outcome"));
    }
    private MarketState locked(String match) {
        jdbc.update("INSERT INTO betting_control.market_state(match_id,status,version) VALUES (?,'UNKNOWN',0) ON CONFLICT DO NOTHING",match);
        var row=jdbc.queryForMap("SELECT * FROM betting_control.market_state WHERE match_id=? FOR UPDATE",match);
        return map(row);
    }
    private MarketState map(Map<String,Object> row) {
        String match=(String)row.get("match_id");var reviews=new LinkedHashMap<String,MarketState.PendingReview>();
        for(var r:jdbc.queryForList("""
                SELECT p.*,e.event_type AS blocking_type FROM betting_control.pending_review p
                JOIN betting_control.processed_event e ON e.match_id=p.match_id AND e.event_id=p.review_id
                WHERE p.match_id=? AND p.closed_by_event_id IS NULL
                """,match)) {
            String id=(String)r.get("review_id");reviews.put(id,new MarketState.PendingReview(id,(String)r.get("reference_event_id"),
                    ((Number)r.get("opened_version")).longValue(),SportEvent.EventType.valueOf((String)r.get("blocking_type"))));
        }
        return new MarketState(match,MarketState.Status.valueOf((String)row.get("status")),((Number)row.get("version")).longValue(),
                (String)row.get("last_event_id"),row.get("updated_at")==null?null:Instant.parse((String)row.get("updated_at")),
                Boolean.TRUE.equals(row.get("inconsistent")),reviews);
    }
    private void persist(MarketState before,MarketState after,String cause) {
        if(before.equals(after)) return;
        int updated=jdbc.update("""
                UPDATE betting_control.market_state SET status=?,version=?,last_event_id=?,updated_at=?,inconsistent=?
                WHERE match_id=? AND version=?
                """,after.status().name(),after.version(),after.lastEventId(),after.updatedAt().toString(),after.inconsistent(),after.matchId(),before.version());
        if(updated!=1) throw new IllegalStateException("Concurrent market revision changed");
        if(before.status()!=after.status()) {
            persistOne("INSERT INTO betting_control.transition_history(match_id,version,previous_status,status,cause_id,changed_at) VALUES (?,?,?,?,?,?)",
                    after.matchId(),after.version(),before.status().name(),after.status().name(),cause,after.updatedAt().toString());
            String id=UUID.randomUUID().toString();String type=after.betsAllowed()?"BETTING_RESUMED":"BETTING_SUSPENDED";
            var payload=new TreeMap<String,Object>();payload.put("message_id",id);payload.put("schema_version",1);
            payload.put("match_id",after.matchId());payload.put("version",after.version());payload.put("event_type",type);
            payload.put("status",after.status().name());payload.put("bets_allowed",after.betsAllowed());payload.put("source_event_id",after.lastEventId());
            payload.put("cause_id",cause);payload.put("updated_at",after.updatedAt().toString());
            persistOne("INSERT INTO betting_control.suspension_outbox(message_id,match_id,version,event_type,payload) VALUES (?::uuid,?,?,?,?)",
                    id,after.matchId(),after.version(),type,json(payload));
        }
    }
    @Override public MarketState closeReview(ReviewClosure command) {
        return closeReview(command,ReviewClosure.ReferenceMode.V1_GOAL);
    }
    @Override public MarketState closeReviewV2(ReviewClosure command) {
        return closeReview(command,ReviewClosure.ReferenceMode.V2_REVIEW);
    }
    private MarketState closeReview(ReviewClosure command,ReviewClosure.ReferenceMode mode) {
        return Objects.requireNonNull(tx.execute(status->{
            var current=locked(command.matchId());
            // Preserve the existing V1 audit image exactly; V2 identities include their interpretation.
            var image=new TreeMap<String,Object>();image.put("reference_mode",mode.name());image.put("closure",command);
            String canonical=mode==ReviewClosure.ReferenceMode.V1_GOAL?json(command):json(image);
            var prior=jdbc.queryForList("SELECT canonical_request FROM betting_control.closure_command WHERE match_id=? AND command_id=?",command.matchId(),command.commandId());
            if(!prior.isEmpty()) {
                if(!canonical.equals(prior.get(0).get("canonical_request"))) throw new IllegalArgumentException("Closure identity conflict");
                return current;
            }
            var continuations=jdbc.queryForList("SELECT * FROM betting_control.processed_event WHERE match_id=? AND event_id=?",command.matchId(),command.continuationEventId());
            if(continuations.isEmpty()) throw new IllegalArgumentException("Continuation not observed");
            var e=continuations.get(0);
            var continuation=new SportEvent(command.continuationEventId(),command.matchId(),SportEvent.EventType.valueOf((String)e.get("event_type")),
                    (String)e.get("team_id"),((Number)e.get("minute")).intValue(),Instant.parse((String)e.get("event_timestamp")),(String)e.get("reference_event_id"));
            var origins=jdbc.queryForList("SELECT event_timestamp FROM betting_control.processed_event WHERE match_id=? AND event_id=?",
                    command.matchId(),command.reviewId());
            if(origins.isEmpty() || continuation.timestamp().isBefore(Instant.parse((String)origins.get(0).get("event_timestamp"))))
                throw new IllegalArgumentException("Continuation predates its blocking event");
            var after=current.close(command,continuation,((Number)e.get("applied_version")).longValue(),clock.instant(),mode);
            persistOne("""
                    INSERT INTO betting_control.closure_command(match_id,command_id,canonical_request,review_id,continuation_event_id,actor,reason,applied_version)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,command.matchId(),command.commandId(),canonical,command.reviewId(),command.continuationEventId(),command.actor(),command.reason(),after.version());
            int closed=jdbc.update("UPDATE betting_control.pending_review SET closed_by_event_id=?,closed_command_id=? WHERE match_id=? AND review_id=? AND closed_by_event_id IS NULL",
                    command.continuationEventId(),command.commandId(),command.matchId(),command.reviewId());
            if(closed!=1) throw new IllegalStateException("Review changed concurrently");
            persist(current,after,command.commandId());return after;
        }));
    }
    @Override public MarketState status(String match) {
        var read=new TransactionTemplate(tx.getTransactionManager());read.setReadOnly(true);
        read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(read.execute(status->{
            var rows=jdbc.queryForList("SELECT * FROM betting_control.market_state WHERE match_id=?",match);
            try { return rows.isEmpty()?MarketState.unknown(match):map(rows.get(0)); }
            catch(IllegalArgumentException failure) { throw new IllegalStateException("Stored market data is inconsistent"); }
        }));
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(Exception failure) { throw new IllegalStateException("Cannot preserve internal audit image"); }
    }
    private void persistOne(String sql,Object... args) {
        if(jdbc.update(sql,args)!=1)throw new IllegalStateException("Market evidence was not persisted");
    }
}
