package com.live_jgv.live_feed_producer.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.live_jgv.live_feed_producer.domain.model.MatchEvent;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Provisional wire format matching the local contract proposal. */
@Component
public final class KafkaMatchEventPublisher implements MatchEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaMatchEventPublisher.class);
    private final KafkaTemplate<String, String> template;
    private final ObjectMapper mapper;
    private final String topic;

    public KafkaMatchEventPublisher(KafkaTemplate<String, String> template, ObjectMapper mapper,
            @Value("${live-feed.kafka.topic:match_events}") String topic) {
        this.template = Objects.requireNonNull(template);
        this.mapper = mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Kafka topic must not be blank");
        }
        this.topic = topic;
    }

    @Override
    public CompletableFuture<Void> publish(MatchEvent event) {
        final String payload;
        try {
            Objects.requireNonNull(event, "event must not be null");
            payload = mapper.writeValueAsString(event);
        } catch (Exception failure) {
            log.warn("Event preparation failed; failureType={}", failure.getClass().getSimpleName());
            return CompletableFuture.failedFuture(new IllegalArgumentException("Could not prepare event JSON", failure));
        }
        try {
            return template.send(topic, event.matchId(), payload).handle((result, failure) -> {
                if (failure != null) {
                    throw new CompletionException(sendFailure(event, failure));
                }
                log.info("Kafka publication acknowledged; eventId={}, topic={}, partition={}, offset={}",
                        event.eventId(), topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                return (Void) null;
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(sendFailure(event, failure));
        }
    }

    private IllegalStateException sendFailure(MatchEvent event, Throwable failure) {
        // Avoid logging payloads, settings or potentially sensitive exception messages.
        log.warn("Kafka publication failed; eventId={}, topic={}, failureType={}",
                event.eventId(), topic, failure.getClass().getSimpleName());
        return new IllegalStateException("Kafka did not acknowledge event publication", failure);
    }
}
