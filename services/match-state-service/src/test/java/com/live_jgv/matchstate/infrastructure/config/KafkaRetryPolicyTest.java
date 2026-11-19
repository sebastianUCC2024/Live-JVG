package com.live_jgv.matchstate.infrastructure.config;

import com.live_jgv.matchstate.infrastructure.kafka.InvalidKafkaMessageException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaRetryPolicyTest {
    @Test
    @SuppressWarnings("unchecked")
    void exhaustedBudgetPausesAndResumesWithoutCommittingOrDiscarding() throws Exception {
        try (var handler = new BoundedRetryErrorHandler(Duration.ofMillis(1), Duration.ofMillis(20))) {
            Consumer<String, String> consumer = mock(Consumer.class);
            var container = mock(MessageListenerContainer.class);
            when(container.isRunning()).thenReturn(true);
            var props = new ContainerProperties("test"); props.setAckMode(ContainerProperties.AckMode.RECORD);
            when(container.getContainerProperties()).thenReturn(props);
            var resumed = new CountDownLatch(1);
            doAnswer(call -> { resumed.countDown(); return null; }).when(container).resume();
            var record = new ConsumerRecord<String,String>("test",0,0,"m","x");
            for (int i=0;i<4;i++) assertThrows(RuntimeException.class, () -> handler.handleRemaining(new IllegalStateException("storage unavailable"),List.of(record),consumer,container));
            verify(container).pause(); assertTrue(resumed.await(2, TimeUnit.SECONDS));
            verify(consumer, never()).commitSync(anyMap()); verify(consumer, never()).commitAsync(anyMap(), any());
            assertFalse(handler.isAckAfterHandle());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedRecordsAreSoughtAgainAndNeverRecoveredOrCommitted() {
        var handler = new MessagingConfiguration().matchEventsErrorHandler(Duration.ofMillis(1), Duration.ofSeconds(30));
        Consumer<String, String> consumer = mock(Consumer.class);
        var container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        var properties = new ContainerProperties("match_events");
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        when(container.getContainerProperties()).thenReturn(properties);
        var record = new ConsumerRecord<String, String>("match_events", 0, 7L, "m1", "invalid-json");
        assertFalse(handler.isAckAfterHandle());
        assertTrue(handler.seeksAfterHandling());
        for (int attempt = 0; attempt < 3; attempt++) {
            var failure = new InvalidKafkaMessageException(new IllegalArgumentException());
            var retry = assertThrows(RuntimeException.class, () -> handler.handleRemaining(
                    failure, List.of(record), consumer, container));
            assertSame(failure, retry.getCause());
        }
        verify(consumer, times(3)).seek(new TopicPartition("match_events", 0), 7L);
        verify(consumer, never()).commitSync(anyMap());
        verify(consumer, never()).commitAsync(anyMap(), any());
        handler.close();
    }
}
