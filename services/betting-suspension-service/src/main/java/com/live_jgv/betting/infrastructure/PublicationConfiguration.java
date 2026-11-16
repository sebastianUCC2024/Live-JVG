package com.live_jgv.betting.infrastructure;

import com.live_jgv.betting.application.SuspensionPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="betting.publication.enabled",havingValue="true")
@EnableScheduling
public class PublicationConfiguration {
    @Bean SuspensionPublisher suspensionPublisher(RabbitTemplate rabbit,
            @Value("${betting.publication.exchange:}") String exchange,@Value("${betting.publication.routing-key:}") String routingKey,
            @Value("${betting.publication.confirm-timeout-ms:5000}") long timeout) {
        return new RabbitSuspensionPublisher(rabbit,exchange,routingKey,timeout);
    }
    @Bean OutboxDispatcher outboxDispatcher(JdbcTemplate jdbc,PlatformTransactionManager tx,SuspensionPublisher publisher) {
        return new OutboxDispatcher(jdbc,new TransactionTemplate(tx),publisher);
    }
}
