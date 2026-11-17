package com.live_jgv.worker.academic;
import org.junit.jupiter.api.Test;import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.*;import com.rabbitmq.client.Channel;
import java.util.concurrent.atomic.AtomicInteger;import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class AcademicRetryTest {
 public interface Delivery{void invoke(Channel channel,Message message);}
 Delivery retrying(Delivery target,RabbitListenerEndpointRegistry registry){var proxy=new ProxyFactory(target);proxy.addAdvice(new AcademicRabbitConfiguration().academicRetryAdvice(registry));return (Delivery)proxy.getProxy();}
 @Test void exhaustedRetriesRetainMessageAndStopOnlyAcademicListener(){var registry=mock(RabbitListenerEndpointRegistry.class);var container=mock(MessageListenerContainer.class);when(registry.getListenerContainer("academicBettingWorker")).thenReturn(container);var attempts=new AtomicInteger();assertThrows(ImmediateRequeueAmqpException.class,()->retrying((channel,message)->{attempts.incrementAndGet();throw new IllegalStateException("offline db failure");},registry).invoke(null,new Message(new byte[0])));assertEquals(4,attempts.get());verify(container).stop(any(Runnable.class));verify(registry,never()).stop(any(Runnable.class));}
 @Test void retryRecoversWithoutStopping(){var registry=mock(RabbitListenerEndpointRegistry.class);var attempts=new AtomicInteger();retrying((channel,message)->{if(attempts.incrementAndGet()<3)throw new IllegalStateException("temporary");},registry).invoke(null,new Message(new byte[0]));assertEquals(3,attempts.get());verifyNoInteractions(registry);}
 @Test void academicConsumerRefusesEnabledAuthorityOrAcceptance(){var config=new AcademicRabbitConfiguration();assertThrows(IllegalStateException.class,()->config.academicCommandStore(null,null,true,"disabled"));assertThrows(IllegalStateException.class,()->config.academicCommandStore(null,null,false,"postgres-atomic"));}
}
