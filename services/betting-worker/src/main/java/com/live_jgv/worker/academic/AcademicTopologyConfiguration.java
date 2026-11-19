package com.live_jgv.worker.academic;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

/**
 * Opt-in academic work queue, normally {@code betting_commands}, reached through RabbitMQ's
 * default exchange with the queue name as routing key. Rejected deliveries use the configured
 * {@code <queue>.dlx} direct exchange, {@code rejected} key and durable {@code <queue>.dlq}.
 * Competing workers share this queue; this is work distribution rather than Kafka log replay.
 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="academic.topology.enabled",havingValue="true")
public class AcademicTopologyConfiguration {
    public static Declarables topology(String queue) {
        if(queue==null||!queue.matches("[A-Za-z0-9_.-]{1,180}"))throw new IllegalArgumentException("Academic destination required");
        return new Declarables(QueueBuilder.durable(queue).deadLetterExchange(queue+".dlx").deadLetterRoutingKey("rejected").build(),
                new DirectExchange(queue+".dlx",true,false),QueueBuilder.durable(queue+".dlq").build(),
                new Binding(queue+".dlq",Binding.DestinationType.QUEUE,queue+".dlx","rejected",null));
    }
    @Bean Declarables academicTopology(@Value("${academic.rabbit.queue:betting_commands}") String queue){return topology(queue);}
    @Bean RabbitAdmin academicTopologyAdmin(ConnectionFactory connection){return new RabbitAdmin(connection);}
}
