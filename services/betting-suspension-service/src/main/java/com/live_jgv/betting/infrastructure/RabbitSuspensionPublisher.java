package com.live_jgv.betting.infrastructure;

import com.live_jgv.betting.application.SuspensionPublisher;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RabbitSuspensionPublisher implements SuspensionPublisher {
    private final RabbitTemplate rabbit;private final String exchange;private final String routingKey;private final long timeoutMs;
    public RabbitSuspensionPublisher(RabbitTemplate rabbit,String exchange,String routingKey,long timeoutMs) {
        if(exchange==null||exchange.isBlank()||routingKey==null||routingKey.isBlank()||timeoutMs<1)
            throw new IllegalArgumentException("Approved publication destination and timeout required");
        this.rabbit=rabbit;this.exchange=exchange;this.routingKey=routingKey;this.timeoutMs=timeoutMs;rabbit.setMandatory(true);
    }
    @Override public void publish(String id,String payload) {
        var properties=new MessageProperties();properties.setMessageId(id);properties.setContentType("application/json");
        properties.setContentEncoding("UTF-8");properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation=new CorrelationData(java.util.UUID.randomUUID().toString());
        try {
            rabbit.send(exchange,routingKey,new Message(payload.getBytes(StandardCharsets.UTF_8),properties),correlation);
            var confirm=correlation.getFuture().get(timeoutMs,TimeUnit.MILLISECONDS);
            if(!confirm.isAck()||correlation.getReturned()!=null) throw new IllegalStateException("Publication not confirmed or unroutable");
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Publication interrupted");
        } catch(Exception failure) { throw new IllegalStateException("Publication outcome is uncertain"); }
    }
}
