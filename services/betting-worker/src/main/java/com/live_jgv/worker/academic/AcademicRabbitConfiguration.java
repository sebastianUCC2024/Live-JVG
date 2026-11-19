package com.live_jgv.worker.academic;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/** Configura el consumidor académico: ACK automático, cuatro intentos y reencolado con parada al agotar reintentos. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="academic.consumer.enabled",havingValue="true")
public class AcademicRabbitConfiguration {
    @Bean AcademicCommandStore academicCommandStore(JdbcTemplate jdbc,PlatformTransactionManager manager,
            @Value("${worker.acceptance.enabled:false}") boolean acceptance,@Value("${worker.authorization.mode:disabled}") String mode){
        if(acceptance||!"disabled".equals(mode))throw new IllegalStateException("Academic consumer requires acceptance OFF and authorization disabled");
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
        return new JdbcAcademicCommandStore(jdbc,tx);
    }
    @Bean AcademicWorkerListener academicWorkerListener(AcademicCommandStore store,RabbitTemplate rabbit,
            @Value("${academic.rabbit.queue:betting_commands}") String queue,@Value("${academic.rabbit.source-id:academic-local/1}") String source,
            @Value("${academic.confirm-timeout-ms:2000}") long timeout){
        return new AcademicWorkerListener(new AcademicCommandHandler(store,new AcademicCommandCodec()),store,
                new AcademicRabbitSender(rabbit,queue+".dlx","rejected",timeout),queue,source);
    }
    @Bean RetryOperationsInterceptor academicRetryAdvice(RabbitListenerEndpointRegistry registry){
        return RetryInterceptorBuilder.stateless().maxAttempts(4).backOffOptions(100,2,400).recoverer((message,failure)->{
            var listener=registry.getListenerContainer("academicBettingWorker");if(listener!=null)listener.stop(()->{});
            throw new ImmediateRequeueAmqpException("Academic delivery retained after retry budget");
        }).build();
    }
    @Bean SimpleRabbitListenerContainerFactory academicRabbitFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connection,RetryOperationsInterceptor academicRetryAdvice){
        var factory=new SimpleRabbitListenerContainerFactory();configurer.configure(factory,connection);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);factory.setDefaultRequeueRejected(true);factory.setMissingQueuesFatal(true);
        factory.setAdviceChain(academicRetryAdvice);factory.setAutoStartup(false);return factory;
    }
    @Bean ApplicationRunner academicWorkerReadiness(JdbcTemplate jdbc,RabbitListenerEndpointRegistry registry){
        return args->{
            jdbc.queryForList("SELECT command_id FROM betting_worker.academic_command LIMIT 0");
            jdbc.queryForList("SELECT delivery_id FROM betting_worker.academic_delivery LIMIT 0");
            var listener=registry.getListenerContainer("academicBettingWorker");
            if(listener==null)throw new IllegalStateException("Academic listener missing");listener.start();
        };
    }
}
