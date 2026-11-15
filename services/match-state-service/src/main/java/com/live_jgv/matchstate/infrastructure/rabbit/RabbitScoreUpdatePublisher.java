package com.live_jgv.matchstate.infrastructure.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import com.live_jgv.matchstate.domain.port.ScoreUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class RabbitScoreUpdatePublisher implements ScoreUpdatePublisher {
    private static final Logger log = LoggerFactory.getLogger(RabbitScoreUpdatePublisher.class);
    private final RabbitTemplate template;
    private final ObjectMapper mapper;
    private final String exchange;
    private final String routingKeyPattern;
    private final long confirmTimeoutMillis;

    public RabbitScoreUpdatePublisher(RabbitTemplate template, ObjectMapper mapper, String exchange,
                                      String routingKeyPattern, Duration confirmTimeout) {
        if (exchange == null || exchange.isBlank() || routingKeyPattern == null
                || !routingKeyPattern.contains("{matchId}") || confirmTimeout.isNegative() || confirmTimeout.isZero()) {
            throw new IllegalArgumentException("Exchange, routing key pattern and positive timeout are required");
        }
        this.template = template;
        this.mapper = mapper;
        this.exchange = exchange;
        this.routingKeyPattern = routingKeyPattern;
        this.confirmTimeoutMillis = confirmTimeout.toMillis();
    }

    @Override
    public void publish(ScoreUpdate update) {
        final byte[] json;
        try {
            json = mapper.writeValueAsBytes(update);
        } catch (Exception failure) {
            log.warn("Score update serialization failed; failureType={}", failure.getClass().getName());
            throw new ScoreUpdatePublicationException("Could not serialize score update", failure);
        }
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(update.eventId());
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        var routingKey = routingKeyPattern.replace("{matchId}", update.matchId());
        try {
            template.send(exchange, routingKey, new Message(json, properties), correlation);
            // A bounded wait is deliberate: Kafka must not commit before Rabbit acknowledges.
            var confirm = correlation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new ScoreUpdatePublicationException("RabbitMQ rejected or could not route score update");
            }
            log.info("RabbitMQ publication acknowledged; eventId={}, exchange={}, routingKey={}",
                    update.eventId(), exchange, routingKey);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ScoreUpdatePublicationException("RabbitMQ confirmation interrupted", failure);
        } catch (Exception failure) {
            log.warn("RabbitMQ publication failed; eventId={}, failureType={}", update.eventId(), failure.getClass().getName());
            if (failure instanceof ScoreUpdatePublicationException publicationFailure) {
                throw publicationFailure;
            }
            throw new ScoreUpdatePublicationException("RabbitMQ publication not confirmed", failure);
        }
    }
}
