package com.live_jgv.worker.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.*;
import com.rabbitmq.client.Channel;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RabbitRetryTest {
    public interface ContainerInvocation{void invoke(Channel channel,Message message);}
    private ContainerInvocation retrying(ContainerInvocation target,RabbitListenerEndpointRegistry registry){
        var proxy=new ProxyFactory(target);proxy.addAdvice(new RabbitWorkerConfiguration().workerRetryAdvice(registry));return (ContainerInvocation)proxy.getProxy();
    }
    @Test void fourFailuresStopListenerAndRequeueWithoutAcknowledging(){
        var registry=mock(RabbitListenerEndpointRegistry.class);var container=mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("simulatedBettingWorker")).thenReturn(container);var attempts=new AtomicInteger();
        var listener=retrying((channel,message)->{attempts.incrementAndGet();throw new IllegalStateException("database unavailable");},registry);
        assertThrows(ImmediateRequeueAmqpException.class,()->listener.invoke(null,new Message(new byte[0])));
        assertEquals(4,attempts.get());verify(container).stop(any(Runnable.class));
    }
    @Test void transientFailureCanSucceedWithinBudgetWithoutStopping(){
        var registry=mock(RabbitListenerEndpointRegistry.class);var attempts=new AtomicInteger();
        retrying((channel,message)->{if(attempts.incrementAndGet()<3)throw new IllegalStateException("temporary");},registry).invoke(null,new Message(new byte[0]));
        assertEquals(3,attempts.get());verifyNoInteractions(registry);
    }
    @Test void unavailableListenerRegistryIsStoppedInsteadOfDiscarding(){
        var registry=mock(RabbitListenerEndpointRegistry.class);
        assertThrows(ImmediateRequeueAmqpException.class,()->new StopAndRequeueRecoverer(registry).recover(new Message(new byte[0]),new RuntimeException()));
        verify(registry).stop(any(Runnable.class));
    }
}
