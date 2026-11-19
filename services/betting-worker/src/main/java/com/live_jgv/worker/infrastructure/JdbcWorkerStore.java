package com.live_jgv.worker.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.live_jgv.worker.application.*;
import com.live_jgv.worker.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

public final class JdbcWorkerStore implements WorkerStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final SuspensionEvidencePort source;
    private final AcceptancePolicy policy;private final Clock clock;private final AtomicAuthorizationPort atomic;
    private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public JdbcWorkerStore(JdbcTemplate jdbc,TransactionTemplate tx,SuspensionEvidencePort source,AcceptancePolicy policy,Clock clock){
        this(jdbc,tx,source,policy,clock,AtomicAuthorizationPort.disabled());
    }
    public JdbcWorkerStore(JdbcTemplate jdbc,TransactionTemplate tx,SuspensionEvidencePort source,AcceptancePolicy policy,Clock clock,AtomicAuthorizationPort atomic){
        this.jdbc=jdbc;this.tx=tx;this.source=source;this.policy=policy;this.clock=clock;this.atomic=atomic;
    }
    @Override public Outcome handle(TransportMessage message,DecodedCommand decoded){
        return Objects.requireNonNull(tx.execute(status->{
            if(decoded.error()!=null){audit(message,null,decoded.error());return Outcome.QUARANTINED;}
            var c=decoded.command();
            int claimed=jdbc.update("""
                INSERT INTO betting_worker.command_inbox(command_id,operation_id,canonical_payload,original_payload,outcome)
                VALUES (?,?,?,?,'PENDING') ON CONFLICT DO NOTHING
                """,c.commandId(),c.operationId(),decoded.canonicalPayload(),message.body());
            if(claimed==0){
                var prior=jdbc.queryForMap("SELECT * FROM betting_worker.command_inbox WHERE command_id=? FOR UPDATE",c.commandId());
                if(!decoded.canonicalPayload().equals(prior.get("canonical_payload"))){audit(message,c.commandId(),"COMMAND_ID_REUSED");return Outcome.IDENTITY_CONFLICT;}
                if("PENDING".equals(prior.get("outcome")))throw new IllegalStateException("Incomplete command receipt");
                persistOne("UPDATE betting_worker.command_inbox SET delivery_attempts=delivery_attempts+1 WHERE command_id=?",c.commandId());
                return "IDENTITY_CONFLICT".equals(prior.get("outcome"))?Outcome.IDENTITY_CONFLICT:Outcome.DUPLICATE;
            }
            var session=atomic.lock(c);
            jdbc.update("INSERT INTO betting_worker.market_guard(match_id) VALUES (?) ON CONFLICT DO NOTHING",c.matchId());
            var guard=guard(jdbc.queryForMap("SELECT * FROM betting_worker.market_guard WHERE match_id=? FOR UPDATE",c.matchId()));
            Instant now=clock.instant();var received=Operation.received(c,now);
            int created=jdbc.update("""
                INSERT INTO betting_worker.operation(operation_id,command_id,match_id,simulated_user_id,status,version,command_timestamp,received_at,updated_at)
                VALUES (?,?,?,?,'RECEIVED',0,?,?,?) ON CONFLICT(operation_id) DO NOTHING
                """,c.operationId(),c.commandId(),c.matchId(),c.simulatedUserId(),c.timestamp().toString(),now.toString(),now.toString());
            if(created==0){audit(message,c.commandId(),"OPERATION_ID_REUSED");complete(c.commandId(),"IDENTITY_CONFLICT");return Outcome.IDENTITY_CONFLICT;}
            history(received,null);
            MarketEvidence evidence;boolean available;
            try{evidence=Objects.requireNonNull(session.authoritative()?session.evidence():source.fetch(c.matchId()));available=evidence.available();}
            catch(RuntimeException failure){evidence=MarketEvidence.unavailable(c.matchId());available=false;}
            Instant decidedAt=clock.instant();
            var policyGuard=guard;
            if(session.authoritative()&&evidence.available()&&!guard.frozen()) {
                var prior=guard.evidence();
                // La renovación bloqueada en base de datos puede conservar la revisión; una lectura antigua no.
                if(!prior.available()||(prior.authority().equals(evidence.authority())&&prior.epoch().equals(evidence.epoch())&&evidence.revision()>=prior.revision()))
                    policyGuard=MarketGuard.unknown(c.matchId());
            }
            var decision=policy.decide(c,evidence,policyGuard,decidedAt);
            if(session.authoritative()&&session.refusal()!=null&&decision.state()==Operation.State.BLOCKED)
                decision=new AcceptancePolicy.Decision(Operation.State.BLOCKED,session.refusal(),guard);
            if(decision.state()==Operation.State.ACCEPTED) {
                var grant=session.authoritative()?session.recordGrant(decoded.canonicalPayload()):new AtomicAuthorizationPort.Grant(false,"ATOMIC_AUTHORIZATION_REQUIRED");
                decision=new AcceptancePolicy.Decision(grant.allowed()?Operation.State.ACCEPTED:Operation.State.BLOCKED,grant.reason(),decision.guard());
            }
            if(session.authoritative()) {
                var next=decision.guard();
                var stable=next.evidence().equals(guard.evidence())&&next.frozen()==guard.frozen()?guard:
                    new MarketGuard(next.evidence(),next.frozen(),Math.addExact(guard.version(),1));
                decision=new AcceptancePolicy.Decision(decision.state(),decision.reason(),stable);
            }
            var terminal=received.finish(decision.state(),decision.reason(),decidedAt);
            if(!guard.equals(decision.guard())) persistGuard(guard,decision.guard());
            persistOne("""
                INSERT INTO betting_worker.operation_evidence(operation_id,observed_payload,guard_payload,source_available,decision_reason,observed_at)
                VALUES (?,?,?,?,?,?)
                """,c.operationId(),json(evidence),json(decision.guard()),available,decision.reason(),decidedAt.toString());
            int finished=jdbc.update("""
                UPDATE betting_worker.operation SET status=?,reason=?,version=?,updated_at=?
                WHERE operation_id=? AND version=? AND status='RECEIVED'
                """,terminal.state().name(),terminal.reason(),terminal.version(),terminal.updatedAt().toString(),c.operationId(),received.version());
            if(finished!=1)throw new IllegalStateException("Concurrent operation revision changed");
            history(terminal,Operation.State.RECEIVED);complete(c.commandId(),"PROCESSED");return Outcome.PROCESSED;
        }));
    }
    private void complete(String command,String outcome){
        if(jdbc.update("UPDATE betting_worker.command_inbox SET outcome=? WHERE command_id=? AND outcome='PENDING'",outcome,command)!=1)
            throw new IllegalStateException("Command not finalized");
    }
    private void audit(TransportMessage m,String command,String reason){
        persistOne("""
            INSERT INTO betting_worker.message_audit(audit_id,source_id,broker_message_id,exchange_name,routing_key,original_payload,original_headers,command_id,reason)
            VALUES (?::uuid,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID().toString(),m.sourceId(),m.messageId(),m.exchange(),m.routingKey(),m.body(),m.headers(),command,reason);
    }
    private MarketGuard guard(Map<String,Object> row){
        String match=(String)row.get("match_id");long revision=((Number)row.get("evidence_revision")).longValue();
        var evidence=revision==0?MarketEvidence.unavailable(match):new MarketEvidence(match,(String)row.get("authority"),(String)row.get("epoch"),revision,
            MarketEvidence.Status.valueOf((String)row.get("status")),Instant.parse((String)row.get("issued_at")),Instant.parse((String)row.get("expires_at")));
        return new MarketGuard(evidence,Boolean.TRUE.equals(row.get("frozen")),((Number)row.get("guard_version")).longValue());
    }
    private void persistGuard(MarketGuard before,MarketGuard after){
        var e=after.evidence();int updated=jdbc.update("""
            UPDATE betting_worker.market_guard SET authority=?,epoch=?,evidence_revision=?,status=?,issued_at=?,expires_at=?,frozen=?,guard_version=?
            WHERE match_id=? AND guard_version=?
            """,e.authority(),e.epoch(),e.revision(),e.status().name(),e.issuedAt()==null?null:e.issuedAt().toString(),e.expiresAt()==null?null:e.expiresAt().toString(),after.frozen(),after.version(),e.matchId(),before.version());
        if(updated!=1)throw new IllegalStateException("Concurrent market fence changed");
    }
    private void history(Operation operation,Operation.State before){
        persistOne("INSERT INTO betting_worker.operation_transition(operation_id,version,previous_status,status,reason,changed_at) VALUES (?,?,?,?,?,?)",
            operation.operationId(),operation.version(),before==null?null:before.name(),operation.state().name(),operation.reason(),operation.updatedAt().toString());
    }
    @Override public Optional<Operation> find(String id){
        var rows=jdbc.queryForList("SELECT * FROM betting_worker.operation WHERE operation_id=?",id);
        if(rows.isEmpty())return Optional.empty();var r=rows.get(0);
        try { return Optional.of(new Operation((String)r.get("operation_id"),(String)r.get("command_id"),(String)r.get("match_id"),(String)r.get("simulated_user_id"),
            Operation.State.valueOf((String)r.get("status")),(String)r.get("reason"),((Number)r.get("version")).longValue(),
            Instant.parse((String)r.get("command_timestamp")),Instant.parse((String)r.get("received_at")),Instant.parse((String)r.get("updated_at")))); }
        catch(RuntimeException failure){throw new IllegalStateException("Stored operation is inconsistent");}
    }
    private void persistOne(String sql,Object... args){
        if(jdbc.update(sql,args)!=1)throw new IllegalStateException("Durable operation write was not completed");
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("Cannot preserve operation evidence");}}
}
