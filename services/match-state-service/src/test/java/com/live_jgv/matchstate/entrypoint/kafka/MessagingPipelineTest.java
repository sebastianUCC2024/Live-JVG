package com.live_jgv.matchstate.entrypoint.kafka;

import com.live_jgv.matchstate.application.service.MatchStateService;
import com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import com.live_jgv.matchstate.infrastructure.memory.InMemoryMatchStateStore;
import com.live_jgv.matchstate.infrastructure.rabbit.RabbitScoreUpdatePublisher;
import com.live_jgv.matchstate.infrastructure.rabbit.ScoreUpdatePublicationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** In-process component test: real adapters/use cases, mocked Rabbit transport, no brokers. */
class MessagingPipelineTest {
    @Test
    void routesAllEventTypesThroughDomainAndPublishesExpectedScoresWithoutDuplicateMessages() throws Exception {
        var mapper = new MessagingConfiguration().messagingObjectMapper();
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            invocation.getArgument(3, CorrelationData.class).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var store = new InMemoryMatchStateStore(List.of(MatchState.start("m1", "home", "away")));
        var time = Instant.parse("2026-11-12T16:00:00Z");
        var processor = new MatchStateService(store, Clock.fixed(time, ZoneOffset.UTC));
        var publisher = new RabbitScoreUpdatePublisher(rabbit, mapper, "live_updates", "score.{matchId}", Duration.ofSeconds(1));
        var listener = new KafkaMatchEventListener(mapper, new ProcessAndPublishMatchEvent(processor, store, publisher));
        var events = List.of(
                new MatchEvent("g1", "m1", EventType.GOAL, "home", 1, Instant.EPOCH, null),
                new MatchEvent("v1", "m1", EventType.VAR_CHECK, null, 2, Instant.EPOCH, "g1"),
                new MatchEvent("a1", "m1", EventType.GOAL_ANNULLED, null, 3, Instant.EPOCH, "g1"),
                new MatchEvent("c1", "m1", EventType.MATCH_CONTINUES, null, 4, Instant.EPOCH, null));
        for (var event : events) {
            var record = new ConsumerRecord<>("match_events", 0, 0L, "m1", mapper.writeValueAsString(event));
            listener.onMessage(record);
            listener.onMessage(record);
        }
        var messages = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbit, times(4)).send(eq("live_updates"), eq("score.m1"), messages.capture(), any(CorrelationData.class));
        for (int index = 0; index < 4; index++) {
            var json = mapper.readTree(messages.getAllValues().get(index).getBody());
            assertEquals(index < 2 ? 1 : 0, json.get("home_score").asInt());
            assertEquals(0, json.get("away_score").asInt());
            assertEquals(events.get(index).eventId(), json.get("event_id").asText());
            assertEquals(index == 1 ? "IN_PROGRESS" : "CLEAR", json.get("var_status").asText());
            assertEquals(time, Instant.parse(json.get("updated_at").asText()));
        }
        assertEquals(new Score(0, 0), store.findById("m1").orElseThrow().score());
        assertEquals(4, store.findById("m1").orElseThrow().processedEvents().size());
    }

    @Test
    void listenerFailsOnNackAndRedeliveryPublishesSamePendingUpdateWithoutApplyingGoalAgain() throws Exception {
        var mapper = new MessagingConfiguration().messagingObjectMapper();
        var rabbit = mock(RabbitTemplate.class);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            invocation.getArgument(3, CorrelationData.class).getFuture().complete(
                    new CorrelationData.Confirm(attempts.incrementAndGet() > 1, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var store = new InMemoryMatchStateStore(List.of(MatchState.start("m1", "home", "away")));
        var publisher = new RabbitScoreUpdatePublisher(rabbit, mapper, "live_updates", "score.{matchId}", Duration.ofSeconds(1));
        var listener = new KafkaMatchEventListener(mapper, new ProcessAndPublishMatchEvent(new MatchStateService(store), store, publisher));
        var goal = new MatchEvent("g1", "m1", EventType.GOAL, "home", 1, Instant.EPOCH, null);
        var record = new ConsumerRecord<>("match_events", 0, 7L, "m1", mapper.writeValueAsString(goal));
        assertThrows(ScoreUpdatePublicationException.class, () -> listener.onMessage(record));
        assertEquals(1, store.pendingForMatch("m1").size());
        listener.onMessage(record);
        listener.onMessage(record);
        var messages = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbit, times(2)).send(eq("live_updates"), eq("score.m1"), messages.capture(), any(CorrelationData.class));
        assertArrayEquals(messages.getAllValues().get(0).getBody(), messages.getAllValues().get(1).getBody());
        assertEquals(new Score(1, 0), store.findById("m1").orElseThrow().score());
        assertTrue(store.pendingForMatch("m1").isEmpty());
    }
}
