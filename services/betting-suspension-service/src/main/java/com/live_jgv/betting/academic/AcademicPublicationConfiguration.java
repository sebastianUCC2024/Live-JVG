package com.live_jgv.betting.academic;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="academic.publication.enabled",havingValue="true")
@EnableScheduling
public class AcademicPublicationConfiguration {
    @Bean AcademicOutboxDispatcher academicOutboxDispatcher(JdbcTemplate jdbc,PlatformTransactionManager manager,RabbitTemplate rabbit,
            @Value("${academic.rabbit.queue:betting_commands}") String queue,@Value("${academic.confirm-timeout-ms:2000}") long timeout){
        var tx=new TransactionTemplate(manager);tx.setTimeout(5);
        return new AcademicOutboxDispatcher(jdbc,tx,new AcademicRabbitSender(rabbit,"",queue,timeout),queue);
    }
    @Bean ApplicationRunner academicOutboxReadiness(JdbcTemplate jdbc){
        return args->jdbc.queryForList("SELECT command_id FROM betting_control.academic_outbox LIMIT 0");
    }
}
