package com.live_jgv.notification.infrastructure.amqp;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public final class ConfirmedDlqRecovery {
    private final RabbitTemplate template;
    private final String exchange;
    private final long timeoutMillis;
    public ConfirmedDlqRecovery(RabbitTemplate template, String exchange, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive confirm timeout required");
        this.template = template; this.exchange = exchange; this.timeoutMillis = timeout.toMillis();
    }
    public void transfer(Message original, Throwable failure) {
        var copy = MessageBuilder.fromClonedMessage(original).setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setExpiration(null).setHeader("failure_type", failure.getClass().getName())
                .setHeader("original_exchange", original.getMessageProperties().getReceivedExchange())
                .setHeader("original_routing_key", original.getMessageProperties().getReceivedRoutingKey()).build();
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            template.send(exchange, "rejected", copy, correlation);
            var confirm = correlation.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) throw new IllegalStateException("DLQ not routed and confirmed");
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CustodyUnavailableException(unavailable);
        }
    }
    public static final class CustodyUnavailableException extends RuntimeException {
        CustodyUnavailableException(Throwable cause) { super("DLQ custody unavailable; original must be retained", cause); }
    }
}
