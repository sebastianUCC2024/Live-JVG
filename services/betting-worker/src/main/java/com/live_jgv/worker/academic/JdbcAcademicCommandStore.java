package com.live_jgv.worker.academic;

import com.live_jgv.worker.domain.TransportMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Per-command academic results, NOT a projection of current authoritative market state. */
public final class JdbcAcademicCommandStore implements AcademicCommandStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public JdbcAcademicCommandStore(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    @Override public Outcome record(AcademicCommand command,TransportMessage original) {
        return Objects.requireNonNull(tx.execute(status->{
            String result=command.commandType()==AcademicCommand.CommandType.SUSPEND_BETS?"SIMULATED_SUSPENDED":"SIMULATED_RESUMED";
            int inserted=jdbc.update("""
                INSERT INTO betting_worker.academic_command(command_id,match_id,source_event_id,command_type,command_timestamp,
                    canonical_payload,simulated_status,ordering_status) VALUES (?,?,?,?,?,?,?,'NO_AUTHORITATIVE_SEQUENCE') ON CONFLICT DO NOTHING
                """,command.commandId(),command.matchId(),command.sourceEventId(),command.commandType().name(),
                    command.timestamp().toString(),command.json(),result);
            Outcome outcome=Outcome.RECORDED;
            if(inserted==0) {
                var rows=jdbc.queryForList("""
                    SELECT command_id,canonical_payload FROM betting_worker.academic_command
                    WHERE command_id=? OR (match_id=? AND source_event_id=?) ORDER BY command_id FOR UPDATE
                    """,command.commandId(),command.matchId(),command.sourceEventId());
                if(rows.isEmpty())throw new IllegalStateException("Academic duplicate identity disappeared");
                if(rows.size()!=1||!command.commandId().equals(rows.get(0).get("command_id"))
                        ||!command.json().equals(rows.get(0).get("canonical_payload")))outcome=Outcome.IDENTITY_CONFLICT;
                else {
                    one("UPDATE betting_worker.academic_command SET delivery_attempts=delivery_attempts+1 WHERE command_id=?",command.commandId());
                    outcome=Outcome.DUPLICATE;
                }
            } else if(inserted!=1)throw new IllegalStateException("Academic command was not stored");
            audit(original,command.commandId(),outcome.name());return outcome;
        }));
    }
    @Override public Outcome quarantine(TransportMessage original,String reason) {
        return Objects.requireNonNull(tx.execute(status->{audit(original,null,reason);return Outcome.QUARANTINED;}));
    }
    private void audit(TransportMessage original,String commandId,String reason) {
        one("""
            INSERT INTO betting_worker.academic_delivery(delivery_id,command_id,source_id,broker_message_id,exchange_name,
                routing_key,original_payload,original_headers,outcome) VALUES (?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID().toString(),commandId,original.sourceId(),original.messageId(),original.exchange(),
                original.routingKey(),original.body(),original.headers(),reason);
    }
    private void one(String sql,Object... args){if(jdbc.update(sql,args)!=1)throw new IllegalStateException("Academic audit write failed");}
    @Override public Optional<Result> find(String commandId) {
        if(commandId==null||commandId.isBlank())throw new IllegalArgumentException("Academic command identity required");
        var rows=jdbc.queryForList("SELECT * FROM betting_worker.academic_command WHERE command_id=?",commandId);
        if(rows.isEmpty())return Optional.empty();var r=rows.get(0);
        return Optional.of(new Result((String)r.get("command_id"),(String)r.get("match_id"),(String)r.get("source_event_id"),
                (String)r.get("command_type"),(String)r.get("simulated_status"),(String)r.get("ordering_status"),((Number)r.get("delivery_attempts")).longValue()));
    }
}
