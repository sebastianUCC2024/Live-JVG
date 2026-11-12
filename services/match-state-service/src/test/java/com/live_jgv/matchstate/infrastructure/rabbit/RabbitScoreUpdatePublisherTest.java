package com.live_jgv.matchstate.infrastructure.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitScoreUpdatePublisherTest {
    private RabbitTemplate template;
    private ObjectMapper mapper;
    private RabbitScoreUpdatePublisher publisher;
    private final ScoreUpdate update = new ScoreUpdate("match-001", 1, 0, "e1", EventType.GOAL_ANNULLED,
            Instant.parse("2026-11-12T15:00:00Z"), VarStatus.CLEAR, "goal-1");

    @BeforeEach
    void setUp() {
        template = mock(RabbitTemplate.class);
        mapper = new MessagingConfiguration().messagingObjectMapper();
        publisher = new RabbitScoreUpdatePublisher(template, mapper, "live_updates", "score.{matchId}", Duration.ofMillis(50));
    }

    private void confirms(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            var correlation = invocation.getArgument(3, CorrelationData.class);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE", "live_updates", "score.match-001"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void sendsJsonToExchangeAndMatchRoutingKeyWithPersistentDeliveryAndStableMessageId() throws Exception {
        confirms(true, false);
        publisher.publish(update);
        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("live_updates"), eq("score.match-001"), message.capture(), any(CorrelationData.class));
        var json = mapper.readTree(message.getValue().getBody());
        var names = new HashSet<String>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("match_id", "home_score", "away_score", "event_id", "event_type", "updated_at", "var_status", "reference_event_id"), names);
        assertEquals("match-001", json.get("match_id").asText());
        assertEquals(1, json.get("home_score").asInt());
        assertEquals(0, json.get("away_score").asInt());
        assertEquals("e1", json.get("event_id").asText());
        assertEquals("GOAL_ANNULLED", json.get("event_type").asText());
        assertEquals("CLEAR", json.get("var_status").asText());
        assertEquals("goal-1", json.get("reference_event_id").asText());
        assertEquals(update.updatedAt(), Instant.parse(json.get("updated_at").asText()));
        assertFalse(new String(message.getValue().getBody(), StandardCharsets.UTF_8).startsWith("\""));
        assertEquals("application/json", message.getValue().getMessageProperties().getContentType());
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getValue().getMessageProperties().getDeliveryMode());
        assertEquals("e1", message.getValue().getMessageProperties().getMessageId());
    }

    @Test
    void supportsConfiguredExchangeAndRoutingPattern() {
        confirms(true, false);
        publisher = new RabbitScoreUpdatePublisher(template, mapper, "custom_exchange", "custom.{matchId}", Duration.ofSeconds(1));
        publisher.publish(update);
        verify(template).send(eq("custom_exchange"), eq("custom.match-001"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void rejectsNegativeAcknowledgement() {
        confirms(false, false);
        assertThrows(ScoreUpdatePublicationException.class, () -> publisher.publish(update));
    }

    @Test
    void treatsUnroutableReturnAsFailureEvenWhenExchangeAcknowledges() {
        confirms(true, true);
        assertThrows(ScoreUpdatePublicationException.class, () -> publisher.publish(update));
    }

    @Test
    void propagatesImmediateSendFailure() {
        doThrow(new IllegalStateException("connection unavailable")).when(template)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(ScoreUpdatePublicationException.class, () -> publisher.publish(update));
    }

    @Test
    void timesOutWhenConfirmationNeverArrives() {
        assertThrows(ScoreUpdatePublicationException.class, () -> publisher.publish(update));
    }

    @Test
    void preservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(ScoreUpdatePublicationException.class, () -> publisher.publish(update));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test
    void serializationFailureDoesNotSend() throws Exception {
        var brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsBytes(update)).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("invalid") {});
        publisher = new RabbitScoreUpdatePublisher(template, brokenMapper, "live_updates", "score.{matchId}", Duration.ofSeconds(1));
        assertThrows(ScoreUpdatePublicationException.class, () -> publisher.publish(update));
        verifyNoInteractions(template);
    }
}
