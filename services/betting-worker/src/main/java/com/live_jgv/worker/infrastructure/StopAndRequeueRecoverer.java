package com.live_jgv.worker.infrastructure;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

/** Retain the failed delivery, stop this service's listener, require operator recovery. */
public final class StopAndRequeueRecoverer implements MessageRecoverer {
    private final RabbitListenerEndpointRegistry registry;
    public StopAndRequeueRecoverer(RabbitListenerEndpointRegistry registry){this.registry=registry;}
    @Override public void recover(Message message,Throwable failure){
        var container=registry.getListenerContainer("simulatedBettingWorker");
        if(container!=null)container.stop(()->{});
        else registry.stop(()->{});
        org.slf4j.LoggerFactory.getLogger(StopAndRequeueRecoverer.class).error("Worker retry budget exhausted; listener stopped and delivery retained");
        throw new ImmediateRequeueAmqpException("Worker delivery retained after retry budget");
    }
}
