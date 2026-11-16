package com.live_jgv.worker.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.amqp.rabbit.listener.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessagingReadinessTest {
    @Test void disabledConsumerDoesNotInspectOrStartBroker(){
        var r=mock(RabbitListenerEndpointRegistry.class);
        assertEquals(Status.OUT_OF_SERVICE,new MessagingReadiness(r,false).health().getStatus());verifyNoInteractions(r);
    }
    @Test void missingStoppedOrDisconnectedConsumerIsNotReady(){
        var r=mock(RabbitListenerEndpointRegistry.class);var h=new MessagingReadiness(r,true);
        assertEquals("LISTENER_MISSING",h.health().getDetails().get("state"));
        var l=mock(SimpleMessageListenerContainer.class);when(r.getListenerContainer("simulatedBettingWorker")).thenReturn(l);
        assertEquals("STOPPED",h.health().getDetails().get("state"));org.springframework.test.util.ReflectionTestUtils.setField(l,"running",true);
        assertEquals("NO_ACTIVE_CONSUMER",h.health().getDetails().get("state"));
    }
    @Test void reconnectRestoresTransportReadinessButNeverAuthorizesBets(){
        var r=mock(RabbitListenerEndpointRegistry.class);var l=mock(SimpleMessageListenerContainer.class);
        when(r.getListenerContainer("simulatedBettingWorker")).thenReturn(l);org.springframework.test.util.ReflectionTestUtils.setField(l,"running",true);
        when(l.getActiveConsumerCount()).thenReturn(1);var h=new MessagingReadiness(r,true);
        assertEquals(Status.UP,h.health().getStatus());assertEquals(false,h.health().getDetails().get("betting_authorized_by_health"));
        when(l.getActiveConsumerCount()).thenReturn(0);assertEquals(Status.OUT_OF_SERVICE,h.health().getStatus());
        verify(l,never()).start();
    }
}
