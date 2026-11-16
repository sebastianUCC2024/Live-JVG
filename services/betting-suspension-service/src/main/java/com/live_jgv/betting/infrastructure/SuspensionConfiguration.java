package com.live_jgv.betting.infrastructure;

import com.live_jgv.betting.application.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.HashMap;

@Configuration(proxyBeanMethods=false)
public class SuspensionConfiguration {
    @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(LeaseSourceVerifier.class)
    LeaseSourceVerifier unavailableLeaseSourceVerifier(){return new UnavailableLeaseSourceVerifier();}
    @Bean EventDecoder eventDecoder() { return new JsonEventDecoder(); }
    @Bean Clock suspensionClock() { return Clock.systemUTC(); }
    @Bean SuspensionStore suspensionStore(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock) {
        var transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(5);
        return new JdbcSuspensionStore(jdbc,transaction,clock);
    }
    @Bean SuspensionService suspensionService(SuspensionStore store,EventDecoder decoder) { return new SuspensionService(store,decoder); }
    @Bean SuspensionErrorHandler suspensionErrorHandler(@Value("${betting.retry-interval-ms:200}") long interval) { return new SuspensionErrorHandler(interval); }
    @Bean ConcurrentKafkaListenerContainerFactory<byte[],byte[]> suspensionContainerFactory(KafkaProperties properties,SuspensionErrorHandler handler) {
        var config=new HashMap<>(properties.buildConsumerProperties());config.put("enable.auto.commit",false);
        config.put("max.poll.records",1);config.put("key.deserializer",org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        config.put("value.deserializer",org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        var factory=new ConcurrentKafkaListenerContainerFactory<byte[],byte[]>();factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setConcurrency(1);factory.setCommonErrorHandler(handler);factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);return factory;
    }
}
