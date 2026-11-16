package com.live_jgv.betting.entrypoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.betting.application.SuspensionService;
import com.live_jgv.betting.domain.InputRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

@Component
public class SuspensionKafkaListener {
    private static final Logger log = LoggerFactory.getLogger(SuspensionKafkaListener.class);
    private final SuspensionService service;
    private final String source;
    private final ObjectMapper mapper = new ObjectMapper();
    public SuspensionKafkaListener(SuspensionService service, @Value("${betting.source-id}") String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Source identity required");
        this.service = service; this.source = source;
    }
    @KafkaListener(id="bettingSuspensionListener", topics="${betting.kafka.topic:match_events}",
            groupId="${betting.kafka.group-id:betting-suspension-service}",
            containerFactory="suspensionContainerFactory", autoStartup="false")
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
            var outcome = service.receive(input);
            log.info("Suspension input completed; partition={}, offset={}, outcome={}", record.partition(), record.offset(), outcome);
        } catch (RuntimeException failure) {
            log.warn("Suspension input failed; partition={}, offset={}, failureType={}", record.partition(), record.offset(), failure.getClass().getSimpleName());
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not preserve Kafka headers");
        }
    }
}
