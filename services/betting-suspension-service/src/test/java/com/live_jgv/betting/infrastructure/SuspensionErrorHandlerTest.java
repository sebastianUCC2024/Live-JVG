package com.live_jgv.betting.infrastructure;

import java.util.List;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.*;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SuspensionErrorHandlerTest {
    @Test void fourFailedDeliveriesPauseWithoutDiscardingOrCommitting() {
        var handler=new SuspensionErrorHandler(1);
        Consumer<byte[],byte[]> consumer=mock(Consumer.class);
        var container=mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        var properties=new ContainerProperties("match_events");properties.setAckMode(ContainerProperties.AckMode.RECORD);
        when(container.getContainerProperties()).thenReturn(properties);
        var record=new ConsumerRecord<byte[],byte[]>("match_events",0,7L,null,null);
        for(int attempt=0;attempt<4;attempt++) {
            assertThrows(RuntimeException.class,()->handler.handleRemaining(
                    new IllegalStateException("storage unavailable"),List.of(record),consumer,container));
        }
        verify(container).pause();
        verify(consumer,times(4)).seek(new TopicPartition("match_events",0),7L);
        assertTrue(mockingDetails(consumer).getInvocations().stream()
                .noneMatch(call->call.getMethod().getName().startsWith("commit")));
        assertFalse(handler.isAckAfterHandle());
        handler.clearThreadState();
    }
    @Test void recordAcknowledgementAndForcedSafeConsumerSettings() {
        var handler=new SuspensionErrorHandler(1);var properties=new KafkaProperties();
        properties.getConsumer().setEnableAutoCommit(true);
        var factory=new SuspensionConfiguration().suspensionContainerFactory(properties,handler);
        var config=factory.getConsumerFactory().getConfigurationProperties();
        assertEquals(false,config.get("enable.auto.commit"));assertEquals(1,config.get("max.poll.records"));
        assertEquals(ContainerProperties.AckMode.RECORD,factory.getContainerProperties().getAckMode());
        assertTrue(factory.getContainerProperties().isSyncCommits());
    }
    @Test void invalidRetryConfigurationFailsFast() {
        assertThrows(IllegalArgumentException.class,()->new SuspensionErrorHandler(0));
    }
}
