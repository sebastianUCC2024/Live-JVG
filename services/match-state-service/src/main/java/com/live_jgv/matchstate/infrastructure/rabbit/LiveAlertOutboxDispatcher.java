package com.live_jgv.matchstate.infrastructure.rabbit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
/** Publica alertas pendientes por AMQP en live_alerts (fanout); conserva pendientes si falla la confirmación. */
public final class LiveAlertOutboxDispatcher {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final RabbitTemplate rabbit; private final long timeout;
    public LiveAlertOutboxDispatcher(JdbcTemplate jdbc,TransactionTemplate tx,RabbitTemplate rabbit,Duration timeout) {
        if(timeout==null || timeout.compareTo(Duration.ofMillis(1))<0) throw new IllegalArgumentException("Positive confirm timeout required");
        this.jdbc=jdbc;this.tx=tx;this.rabbit=rabbit;this.timeout=timeout.toMillis();rabbit.setMandatory(true);
    }
    public boolean drainOne() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var rows=jdbc.queryForList("SELECT o.alert_id,o.payload,o.exchange_name FROM match_state.alert_outbox o WHERE NOT o.published AND NOT EXISTS (SELECT 1 FROM match_state.alert_outbox earlier WHERE earlier.match_id=o.match_id AND earlier.application_seq<o.application_seq AND NOT earlier.published) ORDER BY o.match_id,o.application_seq LIMIT 1 FOR UPDATE OF o SKIP LOCKED");
            if(rows.isEmpty()) return false;
            var row=rows.get(0);var id=row.get("alert_id").toString();
            if(!"live_alerts".equals(row.get("exchange_name"))) throw new IllegalStateException("Invalid stored alert destination");
            var props=new MessageProperties();props.setMessageId(id);props.setContentType("application/json");props.setContentEncoding("UTF-8");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            var correlation=new CorrelationData(UUID.randomUUID().toString());
            try {
                rabbit.send("live_alerts","",new Message(row.get("payload").toString().getBytes(StandardCharsets.UTF_8),props),correlation);
                var confirm=correlation.getFuture().get(timeout,TimeUnit.MILLISECONDS);
                if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("LiveAlert not routed and confirmed");
            } catch(Exception failure) {
                if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("LiveAlert remains pending",failure);
            }
            if(jdbc.update("UPDATE match_state.alert_outbox SET published=TRUE WHERE alert_id=?::uuid AND NOT published",id)!=1) throw new IllegalStateException("Alert publication marker conflict");
            return true;
        }));
    }
}
