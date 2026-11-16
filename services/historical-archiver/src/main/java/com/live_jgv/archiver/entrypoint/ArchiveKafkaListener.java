package com.live_jgv.archiver.entrypoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.archiver.application.ArchiveService;
import com.live_jgv.archiver.domain.InputRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

@Component
public class ArchiveKafkaListener {
    private static final Logger log = LoggerFactory.getLogger(ArchiveKafkaListener.class);
    private final ArchiveService service;
    private final String source;
    private final ObjectMapper mapper = new ObjectMapper();
    public ArchiveKafkaListener(ArchiveService service, @Value("${archive.source-id}") String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Source identity required");
        this.service = service; this.source = source;
    }
    @KafkaListener(id="historicalArchiveListener", topics="${archive.kafka.topic:match_events}",
            groupId="${archive.kafka.group-id:historical-archiver}",
            containerFactory="archiveContainerFactory", autoStartup="false")
    public void receive(ConsumerRecord<byte[],byte[]> record) {
        var headers = new ArrayList<Map<String,Object>>();
        for (var header : record.headers()) {
            var value = new LinkedHashMap<String,Object>();
            value.put("key", header.key());
            value.put("value_base64", header.value() == null ? null : Base64.getEncoder().encodeToString(header.value()));
            headers.add(value);
        }
        try {
            var input = new InputRecord(source, record.topic(), record.partition(), record.offset(), record.timestamp(),
                    record.key(), record.value(), mapper.writeValueAsString(headers));
            var outcome = service.archive(input);
            log.info("Archive completed; partition={}, offset={}, outcome={}", record.partition(), record.offset(), outcome);
        } catch (RuntimeException failure) {
            log.warn("Archive failed; partition={}, offset={}, failureType={}", record.partition(), record.offset(), failure.getClass().getSimpleName());
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not preserve Kafka headers");
        }
    }
}
