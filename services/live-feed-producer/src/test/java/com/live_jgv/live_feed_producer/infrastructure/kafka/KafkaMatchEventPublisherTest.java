package com.live_jgv.live_feed_producer.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.live_feed_producer.domain.model.EventType;
import com.live_jgv.live_feed_producer.domain.model.MatchEvent;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import java.time.Instant;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaMatchEventPublisherTest {
    private KafkaTemplate<String, String> template;
    private ObjectMapper mapper;
    private KafkaMatchEventPublisher publisher;
    private final MatchEvent event = new MatchEvent("annulment-1", "match-1", EventType.GOAL_ANNULLED,
            null, 43, Instant.parse("2026-11-12T15:00:00Z"), "goal-1");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(KafkaTemplate.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        publisher = new KafkaMatchEventPublisher(template, mapper, "match_events");
    }

    @Test
    void publishesProvisionalSnakeCaseJsonWithMatchKeyAndWaitsForAcknowledgement() throws Exception {
        var broker = new CompletableFuture<SendResult<String, String>>();
        when(template.send(eq("match_events"), eq("match-1"), anyString())).thenReturn(broker);
        var completion = publisher.publish(event);
        assertFalse(completion.isDone());
        var payload = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("match_events"), eq("match-1"), payload.capture());
        var json = mapper.readTree(payload.getValue());
        var names = new HashSet<String>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("event_id", "match_id", "event_type", "team_id", "minute", "timestamp", "reference_event_id"), names);
        assertEquals(event.eventId(), json.get("event_id").asText());
        assertEquals(event.matchId(), json.get("match_id").asText());
        assertEquals("GOAL_ANNULLED", json.get("event_type").asText());
        assertEquals(43, json.get("minute").asInt());
        assertEquals(event.timestamp(), Instant.parse(json.get("timestamp").asText()));
        assertEquals("goal-1", json.get("reference_event_id").asText());
        assertTrue(json.get("team_id").isNull());
        broker.complete(ack("match_events"));
        assertNull(completion.join());
        assertTrue(mapper.readTree(mapper.writeValueAsString(event)).has("matchId"));
    }

    @Test
    void preservesGoalTeamAndSupportsConfiguredTopic() throws Exception {
        publisher = new KafkaMatchEventPublisher(template, mapper, "custom_events");
        when(template.send(eq("custom_events"), eq("match-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(ack("custom_events")));
        publisher.publish(new MatchEvent("goal-1", "match-1", EventType.GOAL, "team-1", 42, event.timestamp(), null)).join();
        var payload = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("custom_events"), eq("match-1"), payload.capture());
        assertEquals("team-1", mapper.readTree(payload.getValue()).get("team_id").asText());
    }

    @Test
    void propagatesAsynchronousBrokerFailure() {
        var broker = new CompletableFuture<SendResult<String, String>>();
        when(template.send(anyString(), anyString(), anyString())).thenReturn(broker);
        var completion = publisher.publish(event);
        var cause = new RuntimeException("broker unavailable");
        broker.completeExceptionally(cause);
        var failure = assertThrows(CompletionException.class, completion::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertSame(cause, failure.getCause().getCause());
    }

    @Test
    void propagatesImmediateSendFailure() {
        var cause = new RuntimeException("metadata unavailable");
        when(template.send(anyString(), anyString(), anyString())).thenThrow(cause);
        var completion = assertDoesNotThrow(() -> publisher.publish(event));
        assertSame(cause, assertThrows(CompletionException.class, completion::join).getCause().getCause());
    }

    @Test
    void distinguishesSerializationFailureAndDoesNotSend() throws Exception {
        var brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.copy()).thenReturn(brokenMapper);
        when(brokenMapper.setPropertyNamingStrategy(any())).thenReturn(brokenMapper);
        when(brokenMapper.disable(any(com.fasterxml.jackson.databind.SerializationFeature.class))).thenReturn(brokenMapper);
        when(brokenMapper.writeValueAsString(event)).thenThrow(new JsonProcessingException("invalid JSON") {});
        publisher = new KafkaMatchEventPublisher(template, brokenMapper, "match_events");
        var failure = assertThrows(CompletionException.class, () -> publisher.publish(event).join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertInstanceOf(JsonProcessingException.class, failure.getCause().getCause());
        verifyNoInteractions(template);
    }

    @Test
    void rejectsNullEventWithoutSending() {
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(CompletionException.class, () -> publisher.publish(null).join()).getCause());
        verifyNoInteractions(template);
    }

    @Test
    void retriesKeepIdenticalKafkaKeyAndWirePayload() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException()),
                CompletableFuture.completedFuture(ack("match_events")));
        var service = new com.live_jgv.live_feed_producer.application.service.MatchSimulationService(publisher, "match-1", "home", "away");
        var failure = assertThrows(CompletionException.class, () -> service.publish("match-1", null,
                EventType.GOAL, "home", 10, null, null).join());
        var original = assertInstanceOf(com.live_jgv.live_feed_producer.application.service.EventPublicationException.class,
                failure.getCause()).event();
        var retried = service.publish(original.matchId(), original.eventId(), original.eventType(), original.teamId(),
                original.minute(), original.timestamp(), original.referenceEventId()).join();
        assertEquals(original, retried);
        var payloads = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).send(eq("match_events"), eq("match-1"), payloads.capture());
        assertEquals(payloads.getAllValues().get(0), payloads.getAllValues().get(1));
    }

    @Test
    void rejectsBlankTopic() {
        assertThrows(IllegalArgumentException.class, () -> new KafkaMatchEventPublisher(template, mapper, " "));
    }

    private SendResult<String, String> ack(String topic) {
        return new SendResult<>(new ProducerRecord<>(topic, event.matchId(), "{}"),
                new RecordMetadata(new TopicPartition(topic, 0), 7, 0, 0, 0, 0));
    }
}
