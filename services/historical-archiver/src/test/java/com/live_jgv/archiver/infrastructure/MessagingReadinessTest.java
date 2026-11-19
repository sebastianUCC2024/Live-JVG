package com.live_jgv.archiver.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.apache.kafka.common.TopicPartition;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessagingReadinessTest {
    @Test void disabledListenerCannotBeOperational(){
        var registry=mock(KafkaListenerEndpointRegistry.class);
        assertEquals(Status.OUT_OF_SERVICE,new MessagingReadiness(registry,false).health().getStatus());verifyNoInteractions(registry);
    }
    @Test void missingStoppedPausedOrUnassignedListenerCannotBeReady(){
        var registry=mock(KafkaListenerEndpointRegistry.class);var health=new MessagingReadiness(registry,true);
        assertEquals(Status.OUT_OF_SERVICE,health.health().getStatus());
        var listener=mock(MessageListenerContainer.class);when(registry.getListenerContainer("historicalArchiveListener")).thenReturn(listener);
        assertEquals("STOPPED",health.health().getDetails().get("state"));
        when(listener.isRunning()).thenReturn(true);when(listener.isPauseRequested()).thenReturn(true);
        assertEquals("PAUSED",health.health().getDetails().get("state"));
        when(listener.isPauseRequested()).thenReturn(false);when(listener.isContainerPaused()).thenReturn(true);
        assertEquals("PAUSED",health.health().getDetails().get("state"));
        when(listener.isContainerPaused()).thenReturn(false);
        assertEquals("NO_ASSIGNMENT",health.health().getDetails().get("state"));
    }
    @Test void assignedRunningListenerRecoversReadinessWithoutResumingIt(){
        var registry=mock(KafkaListenerEndpointRegistry.class);var listener=mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("historicalArchiveListener")).thenReturn(listener);when(listener.isRunning()).thenReturn(true);
        when(listener.getAssignedPartitions()).thenReturn(List.of(new TopicPartition("match_events",0)));
        var health=new MessagingReadiness(registry,true);assertEquals(Status.UP,health.health().getStatus());
        assertEquals(false,health.health().getDetails().get("lag_verified"));
        when(listener.isPauseRequested()).thenReturn(true);assertEquals(Status.OUT_OF_SERVICE,health.health().getStatus());
        when(listener.isPauseRequested()).thenReturn(false);assertEquals(Status.UP,health.health().getStatus());
        verify(listener,never()).start();verify(listener,never()).resume();
    }
}
