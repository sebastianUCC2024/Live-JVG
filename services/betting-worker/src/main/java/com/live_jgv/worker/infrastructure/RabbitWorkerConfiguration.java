package com.live_jgv.worker.infrastructure;

import com.live_jgv.worker.application.WorkerService;
import com.live_jgv.worker.entrypoint.WorkerRabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="worker.consumer.enabled",havingValue="true")
public class RabbitWorkerConfiguration {
    @Bean WorkerRabbitListener workerRabbitListener(WorkerService service,@Value("${worker.rabbit.source-id}") String source,
            @Value("${worker.rabbit.exchange}") String exchange,@Value("${worker.rabbit.queue}") String queue){
        if(queue==null||queue.isBlank())throw new IllegalArgumentException("Approved queue required");return new WorkerRabbitListener(service,source,exchange);
    }
    @Bean RetryOperationsInterceptor workerRetryAdvice(RabbitListenerEndpointRegistry registry){
        return RetryInterceptorBuilder.stateless().maxAttempts(4).backOffOptions(100,2,400)
            .recoverer(new StopAndRequeueRecoverer(registry)).build();
    }
    @Bean SimpleRabbitListenerContainerFactory workerRabbitFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connection,RetryOperationsInterceptor workerRetryAdvice){
        var factory=new SimpleRabbitListenerContainerFactory();configurer.configure(factory,connection);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);factory.setDefaultRequeueRejected(true);factory.setMissingQueuesFatal(true);
        factory.setAdviceChain(workerRetryAdvice);factory.setAutoStartup(false);return factory;
    }
}
