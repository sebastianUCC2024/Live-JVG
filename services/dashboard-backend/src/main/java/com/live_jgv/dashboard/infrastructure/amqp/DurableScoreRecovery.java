package com.live_jgv.dashboard.infrastructure.amqp;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

/** Confirma la entrada únicamente después de asegurar su copia en la cola de fallos. */
public final class DurableScoreRecovery implements MessageRecoverer {
    private final RabbitTemplate template;
    private final String exchange;
    private final long timeoutMillis;
    private final Runnable stopConsumer;

    public DurableScoreRecovery(RabbitTemplate template, String exchange, Duration timeout, Runnable stopConsumer) {
        if (timeout.toMillis() < 1) throw new IllegalArgumentException("Positive confirm timeout required");
        this.template = template;
        this.exchange = exchange;
        this.timeoutMillis = timeout.toMillis();
        this.stopConsumer = stopConsumer;
    }

    @Override
    public void recover(Message original, Throwable failure) {
        var copy = MessageBuilder.fromClonedMessage(original)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader("failure_type", failure.getClass().getName())
                .setHeader("original_exchange", original.getMessageProperties().getReceivedExchange())
                .setHeader("original_routing_key", original.getMessageProperties().getReceivedRoutingKey()).build();
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            template.send(exchange, "rejected", copy, correlation);
            var confirm = correlation.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("Failure publication not routed and confirmed");
            }
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            // Conserva la entrada ante fallos de custodia, sin descartarla ni repetirla indefinidamente.
            try { stopConsumer.run(); }
            finally { throw new ImmediateRequeueAmqpException("DLQ custody unavailable; consumer stopped; input retained"); }
        }
    }
}
