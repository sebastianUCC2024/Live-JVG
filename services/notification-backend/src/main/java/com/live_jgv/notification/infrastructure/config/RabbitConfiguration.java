package com.live_jgv.notification.infrastructure.config;

import com.live_jgv.notification.application.ProcessScoreUpdateService;
import com.live_jgv.notification.infrastructure.amqp.*;
import com.live_jgv.notification.infrastructure.json.ScoreUpdateCodec;
import java.time.Duration;
import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableRabbit
@ConditionalOnProperty(name="notification.messaging.enabled", havingValue="true")
public class RabbitConfiguration {
    public static Declarables topology(String exchange, String queue, String matchIds) {
        if (!queue.startsWith("notification.") || !queue.matches("[A-Za-z0-9_.-]{1,200}"))
            throw new IllegalArgumentException("A notification-owned queue is required");
        if (exchange.isBlank() || matchIds.isBlank()) throw new IllegalArgumentException("Exchange and approved match IDs required");
        var declarations = new ArrayList<Declarable>();
        var topic = new TopicExchange(exchange, true, false);
        var input = new org.springframework.amqp.core.Queue(queue, true);
        declarations.add(topic); declarations.add(input);
        for (String match : new LinkedHashSet<>(Arrays.asList(matchIds.split(",", -1)))) {
            match = match.trim();
            if (!match.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Explicit safe match IDs required");
            declarations.add(BindingBuilder.bind(input).to(topic).with("score."+match));
        }
        String dlx=queue+".dlx", dlq=queue+".dlq";
        declarations.add(new DirectExchange(dlx,true,false));
        declarations.add(new org.springframework.amqp.core.Queue(dlq,true));
        declarations.add(new Binding(dlq,Binding.DestinationType.QUEUE,dlx,"rejected",null));
        return new Declarables(declarations);
    }
    @Bean Declarables notificationTopology(@Value("${notification.messaging.exchange}") String exchange,
            @Value("${notification.messaging.queue}") String queue,@Value("${notification.messaging.match-ids:}") String ids,
            @Value("${notification.storage.enabled:false}") boolean storage) {
        if (!storage) throw new IllegalArgumentException("Durable storage must be enabled before messaging");
        return topology(exchange,queue,ids);
    }
    @Bean RabbitAdmin notificationAdmin(ConnectionFactory connectionFactory) { return new RabbitAdmin(connectionFactory); }
    @Bean public RabbitTemplate notificationRabbitTemplate(ConnectionFactory connectionFactory) {
        var publisher=connectionFactory.getPublisherConnectionFactory();
        if (publisher==null) publisher=connectionFactory;
        if (!publisher.isPublisherConfirms() || publisher.isSimplePublisherConfirms() || !publisher.isPublisherReturns())
            throw new IllegalArgumentException("Correlated publisher confirms and returns are required for DLQ custody");
        var template = new RabbitTemplate(connectionFactory); template.setMandatory(true); return template;
    }
    @Bean SimpleRabbitListenerContainerFactory notificationListenerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory(); factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL); factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1); factory.setMaxConcurrentConsumers(1); factory.setDefaultRequeueRejected(false);
        return factory;
    }
    @Bean ConfirmedDlqRecovery confirmedDlqRecovery(RabbitTemplate template,
            @Value("${notification.messaging.queue}") String queue,@Value("${notification.messaging.confirm-timeout:5s}") Duration timeout) {
        return new ConfirmedDlqRecovery(template,queue+".dlx",timeout);
    }
    @Bean ScoreUpdateListener notificationListener(ProcessScoreUpdateService processor, ScoreUpdateCodec codec,
            ConfirmedDlqRecovery recovery, RabbitListenerEndpointRegistry registry,
            @Value("${notification.messaging.max-attempts:3}") int attempts,@Value("${notification.messaging.retry-interval-ms:1000}") long interval) {
        return new ScoreUpdateListener(processor,codec,recovery,()->{
            var container=registry.getListenerContainer("notificationScores");
            if (container != null) container.stop(()->{});
        },Thread::sleep,attempts,interval);
    }
}
