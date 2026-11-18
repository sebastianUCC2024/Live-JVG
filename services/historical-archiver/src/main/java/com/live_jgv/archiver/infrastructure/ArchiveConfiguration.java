package com.live_jgv.archiver.infrastructure;

import com.live_jgv.archiver.application.*;
import com.live_jgv.archiver.infrastructure.jpa.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.HashMap;

@Configuration(proxyBeanMethods=false)
public class ArchiveConfiguration {
    @Bean EventDecoder eventDecoder() { return new JsonEventDecoder(); }
    @Bean
    @ConditionalOnProperty(name="archive.storage.adapter",havingValue="jdbc")
    ArchiveStore jdbcArchiveStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcArchiveStore(jdbc, new TransactionTemplate(manager));
    }
    @Bean
    @ConditionalOnProperty(name="archive.storage.adapter",havingValue="jpa",matchIfMissing=true)
    ArchiveStore jpaArchiveStore(ArchivedEventRepository events, InputReceiptRepository receipts, PlatformTransactionManager manager) {
        return new JpaArchiveStore(events, receipts, manager);
    }
    @Bean ArchiveService archiveService(ArchiveStore store, EventDecoder decoder) { return new ArchiveService(store, decoder); }
    @Bean ArchiverErrorHandler archiveErrorHandler(@Value("${archive.retry-interval-ms:200}") long interval) {
        return new ArchiverErrorHandler(interval);
    }
    @Bean ConcurrentKafkaListenerContainerFactory<byte[],byte[]> archiveContainerFactory(
            KafkaProperties properties, ArchiverErrorHandler handler) {
        var config = new HashMap<>(properties.buildConsumerProperties());
        config.put("enable.auto.commit", false);
        config.put("key.deserializer", org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        config.put("value.deserializer", org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        config.put("max.poll.records", 1);
        var factory = new ConcurrentKafkaListenerContainerFactory<byte[],byte[]>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(handler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        return factory;
    }
}
