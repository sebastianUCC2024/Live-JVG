package com.live_jgv.notification.infrastructure.health;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.event.AmqpEvent;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.context.ApplicationListener;

/** El contenedor activo no demuestra que RabbitMQ haya aceptado basic.consume. */
public final class ConsumerReadiness implements ApplicationListener<AmqpEvent>, ConnectionListener {
    private final String queue;
    private final AtomicBoolean accepted=new AtomicBoolean();
    public ConsumerReadiness(String queue) { this.queue=queue; }
    public boolean accepted() { return accepted.get(); }
    public void invalidate() { accepted.set(false); }
    @Override public void onApplicationEvent(AmqpEvent event) {
        if (event instanceof ConsumeOkEvent ok && queue.equals(ok.getQueue())) accepted.set(true);
        else if (event instanceof ListenerContainerConsumerFailedEvent || event instanceof AsyncConsumerStoppedEvent
                || event instanceof AsyncConsumerRestartedEvent || event instanceof ListenerContainerConsumerTerminatedEvent) invalidate();
    }
    @Override public void onCreate(Connection connection) { invalidate(); }
    @Override public void onClose(Connection connection) { invalidate(); }
    @Override public void onShutDown(com.rabbitmq.client.ShutdownSignalException signal) { invalidate(); }
}
