package com.live_jgv.betting.infrastructure;

import com.live_jgv.betting.application.SuspensionPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Publica la primera versión pendiente por partido; un timeout revierte la marca. */
public final class OutboxDispatcher {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final SuspensionPublisher publisher;
    public OutboxDispatcher(JdbcTemplate jdbc,TransactionTemplate tx,SuspensionPublisher publisher) { this.jdbc=jdbc;this.tx=tx;this.publisher=publisher; }
    @Scheduled(fixedDelayString="${betting.publication.delay-ms:1000}")
    public void scheduledDrain() {
        try { drainOne(); }
        catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(OutboxDispatcher.class).warn("Outbox publication retained; failureType={}",failure.getClass().getSimpleName()); }
    }
    public boolean drainOne() {
        return Boolean.TRUE.equals(tx.execute(status->{
            var rows=jdbc.queryForList("""
                    SELECT o.message_id,o.payload FROM betting_control.suspension_outbox o
                    WHERE o.published_at IS NULL AND NOT EXISTS (
                        SELECT 1 FROM betting_control.suspension_outbox earlier
                        WHERE earlier.match_id=o.match_id AND earlier.version<o.version AND earlier.published_at IS NULL)
                    ORDER BY o.created_at,o.match_id,o.version LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                    """);
            if(rows.isEmpty()) return false;
            var row=rows.get(0);String id=row.get("message_id").toString();publisher.publish(id,(String)row.get("payload"));
            int updated=jdbc.update("UPDATE betting_control.suspension_outbox SET published_at=CURRENT_TIMESTAMP WHERE message_id=?::uuid AND published_at IS NULL",id);
            if(updated!=1) throw new IllegalStateException("Outbox publication marker failed");
            return true;
        }));
    }
}
