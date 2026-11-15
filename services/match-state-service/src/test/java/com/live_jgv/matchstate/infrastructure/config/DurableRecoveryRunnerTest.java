package com.live_jgv.matchstate.infrastructure.config;

import com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent;
import com.live_jgv.matchstate.infrastructure.jdbc.JdbcMatchStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DurableRecoveryRunnerTest {
    @Test
    void failedDrainRetriesBeforeStartingKafka() throws Exception {
        var store = mock(JdbcMatchStateStore.class);
        var processor = mock(ProcessAndPublishMatchEvent.class);
        var registry = mock(KafkaListenerEndpointRegistry.class);
        var context = mock(ConfigurableApplicationContext.class);
        when(context.isActive()).thenReturn(true);
        when(store.matchIds()).thenReturn(List.of("match"));
        doThrow(new IllegalStateException("temporary publication failure")).doNothing().when(processor).recoverPendingMatch("match");
        new DurableRecoveryRunner(store, processor, registry, context, true, Duration.ofMillis(1)).run(null);
        var order = inOrder(processor, registry);
        order.verify(processor, times(2)).recoverPendingMatch("match");
        order.verify(registry).start();
    }

    @Test
    void closingContextDuringFailedRecoveryNeverStartsKafka() {
        var store = mock(JdbcMatchStateStore.class);
        var processor = mock(ProcessAndPublishMatchEvent.class);
        var registry = mock(KafkaListenerEndpointRegistry.class);
        var context = mock(ConfigurableApplicationContext.class);
        when(context.isActive()).thenReturn(true, false);
        when(store.matchIds()).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class, () -> new DurableRecoveryRunner(store, processor, registry, context, true, Duration.ofMillis(1)).run(null));
        verifyNoInteractions(registry, processor);
    }

    @Test
    void listenerDisableFlagIsPreservedAfterSuccessfulDrain() throws Exception {
        var store = mock(JdbcMatchStateStore.class);
        var registry = mock(KafkaListenerEndpointRegistry.class);
        var context = mock(ConfigurableApplicationContext.class);
        when(context.isActive()).thenReturn(true);
        when(store.matchIds()).thenReturn(List.of());
        new DurableRecoveryRunner(store, mock(ProcessAndPublishMatchEvent.class), registry, context, false, Duration.ofMillis(1)).run(null);
        verifyNoInteractions(registry);
    }
}
