package com.live_jgv.dashboard.infrastructure.amqp;

import com.live_jgv.dashboard.application.service.ProcessScoreUpdateService;
import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import com.live_jgv.dashboard.application.port.in.ProcessScoreUpdate;
import com.live_jgv.dashboard.application.port.in.GetMatchSnapshot;
import com.live_jgv.dashboard.application.port.out.MatchSnapshotPublisher;
import com.live_jgv.dashboard.application.service.GetMatchSnapshotService;
import com.live_jgv.dashboard.infrastructure.memory.InMemoryDashboardProjection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;

@org.springframework.context.annotation.Profile("memory")
@Configuration(proxyBeanMethods = false)
public class DashboardRabbitConfiguration {
    private static final Logger log = LoggerFactory.getLogger(DashboardRabbitConfiguration.class);

    @Bean
    public Declarables dashboardTopology(
            @Value("${dashboard.rabbit.exchange}") String exchange,
            @Value("${dashboard.rabbit.queue}") String queue,
            @Value("${dashboard.rabbit.binding-key}") String bindingKey,
            @Value("${dashboard.rabbit.dead-letter-exchange}") String dlx,
            @Value("${dashboard.rabbit.dead-letter-queue}") String dlq,
            @Value("${dashboard.rabbit.dead-letter-key}") String deadKey) {
        return new Declarables(new TopicExchange(exchange, true, false),
                QueueBuilder.durable(queue).deadLetterExchange(dlx).deadLetterRoutingKey(deadKey).build(),
                new Binding(queue, Binding.DestinationType.QUEUE, exchange, bindingKey, null),
                new DirectExchange(dlx, true, false), QueueBuilder.durable(dlq).build(),
                new Binding(dlq, Binding.DestinationType.QUEUE, dlx, deadKey, null));
    }

    @Bean
    public InMemoryDashboardProjection dashboardProjection(@Value("${dashboard.projection.max-events}") int limit,
            MatchSnapshotPublisher publisher) {
        return new InMemoryDashboardProjection(limit, publisher);
    }

    @Bean
    public GetMatchSnapshot getMatchSnapshot(InMemoryDashboardProjection projection) {
        return new GetMatchSnapshotService(projection);
    }

    @Bean
    public ProcessScoreUpdate processScoreUpdate(InMemoryDashboardProjection projection) {
        return new ProcessScoreUpdateService(projection);
    }

    @Bean
    public ScoreUpdateListener scoreUpdateListener(ProcessScoreUpdate processor) {
        return new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), processor);
    }

    @Bean
    public RetryOperationsInterceptor dashboardRetryAdvice(
            @Value("${dashboard.rabbit.max-attempts}") int attempts,
            @Value("${dashboard.rabbit.retry-interval-ms}") long interval) {
        if (attempts < 1 || interval < 1) {
            throw new IllegalArgumentException("Retry attempts and interval must be positive");
        }
        var policy = new SimpleRetryPolicy(attempts, Map.of(
                InvalidScoreUpdateMessageException.class, false,
                InvalidScoreUpdateException.class, false), true, true);
        return RetryInterceptorBuilder.stateless().retryPolicy(policy)
                .backOffOptions(interval, 1.0, interval)
                .recoverer((message, failure) -> {
                    log.warn("Score update rejected after processing failure; failureType={}", failure.getClass().getName());
                    throw new AmqpRejectAndDontRequeueException("Score update rejected");
                }).build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory dashboardListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory, RetryOperationsInterceptor dashboardRetryAdvice) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(dashboardRetryAdvice);
        factory.setErrorHandler(failure -> {
            // Do not log ListenerExecutionFailedException: it can contain the raw body.
            log.warn("Dashboard listener rejected a message; failureType={}", failure.getClass().getName());
            throw new AmqpRejectAndDontRequeueException("Dashboard listener rejected message");
        });
        return factory;
    }
}
