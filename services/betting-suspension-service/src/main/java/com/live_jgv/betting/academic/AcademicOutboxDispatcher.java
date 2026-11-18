package com.live_jgv.betting.academic;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class AcademicOutboxDispatcher {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final AcademicRabbitSender sender;private final String queue;
    public AcademicOutboxDispatcher(JdbcTemplate jdbc,TransactionTemplate tx,AcademicRabbitSender sender,String queue){this.jdbc=jdbc;this.tx=tx;this.sender=sender;this.queue=queue;}
    @Scheduled(fixedDelayString="${academic.publication.delay-ms:1000}")
    public void scheduledDrain(){
        try{drainOne();}catch(RuntimeException retained){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Academic outbox retained; failureType={}",retained.getClass().getSimpleName());}
    }
    public boolean drainOne() {
        return Boolean.TRUE.equals(tx.execute(status->{
            var rows=jdbc.queryForList("""
                SELECT o.command_id,o.payload,o.destination_queue FROM betting_control.academic_outbox o
                WHERE o.published_at IS NULL AND NOT EXISTS (
                    SELECT 1 FROM betting_control.academic_outbox earlier WHERE earlier.match_id=o.match_id
                    AND earlier.source_version<o.source_version AND earlier.published_at IS NULL)
                ORDER BY o.created_at,o.match_id,o.source_version LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """);
            if(rows.isEmpty())return false;
            var row=rows.get(0);String id=(String)row.get("command_id");
            if(!queue.equals(row.get("destination_queue")))throw new IllegalStateException("Academic destination changed; review required");
            sender.send(id,((String)row.get("payload")).getBytes(StandardCharsets.UTF_8),Map.of());
            if(jdbc.update("UPDATE betting_control.academic_outbox SET published_at=CURRENT_TIMESTAMP WHERE command_id=? AND published_at IS NULL",id)!=1)
                throw new IllegalStateException("Academic publication marker missing");
            return true;
        }));
    }
}
