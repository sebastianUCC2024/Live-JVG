package com.live_jgv.matchstate.entrypoint.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.matchstate.domain.model.MatchEvent;
import com.live_jgv.matchstate.domain.port.ProcessMatchEvent;
import com.live_jgv.matchstate.domain.port.IncomingEventHandler;
import com.live_jgv.matchstate.domain.model.InputRecord;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

public final class KafkaMatchEventListener {
    private static final Logger log = LoggerFactory.getLogger(KafkaMatchEventListener.class);
    private final ObjectMapper mapper;
    private final ProcessMatchEvent processor;
    private final IncomingEventHandler ingress;
    private final Function<String, String> source;

    public KafkaMatchEventListener(ObjectMapper mapper, ProcessMatchEvent processor) {
        this(mapper, processor, null, "memory-fixture");
    }

    public KafkaMatchEventListener(ObjectMapper mapper, ProcessMatchEvent processor, IncomingEventHandler ingress, String source) {
        this(mapper, processor, ingress, topic -> source);
    }

    public KafkaMatchEventListener(ObjectMapper mapper, ProcessMatchEvent processor, IncomingEventHandler ingress, Function<String, String> source) {
        this.mapper = mapper;
        this.processor = processor;
        this.ingress = ingress;
        this.source = source;
    }

    @KafkaListener(id = "matchEventsListener", topics = "${match-state.kafka.topic:match_events}",
            groupId = "${spring.kafka.consumer.group-id:match-state-service}",
            containerFactory = "matchEventsContainerFactory")
    public void onRawMessage(ConsumerRecord<byte[], byte[]> record) {
        if (ingress != null) {
            var headers = new ArrayList<Map<String, Object>>();
            record.headers().forEach(h -> {
                var header = new LinkedHashMap<String, Object>(); header.put("name", h.key());
                header.put("value_base64", h.value() == null ? null : Base64.getEncoder().encodeToString(h.value())); headers.add(header);
            });
            final String serializedHeaders;
            try { serializedHeaders = mapper.writeValueAsString(headers); }
            catch (Exception failure) { throw new IllegalStateException("Cannot preserve input headers", failure); }
            ingress.handle(new InputRecord(source.apply(record.topic()), record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), record.value(), serializedHeaders));
        } else {
            onMessage(new ConsumerRecord<>(record.topic(), record.partition(), record.offset(),
                    record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8),
                    record.value() == null ? null : new String(record.value(), StandardCharsets.UTF_8)));
        }
    }

    /** In-process adapter entry retained for memory/component fixtures, without durable custody. */
    public void onMessage(ConsumerRecord<String, String> record) {
        final MatchEvent event;
        try {
            event = mapper.readValue(record.value(), MatchEventMessage.class).toDomain();
            if (!event.matchId().equals(record.key())) {
                throw new IllegalArgumentException("Kafka key must equal match ID");
            }
        } catch (Exception failure) {
            log.warn("Kafka input rejected; partition={}, offset={}, failureType={}",
                    record.partition(), record.offset(), failure.getClass().getName());
            throw new InvalidKafkaMessageException(failure);
        }
        try {
            processor.process(event);
            log.info("Kafka event handling completed; eventId={}, partition={}, offset={}",
                    event.eventId(), record.partition(), record.offset());
        } catch (RuntimeException failure) {
            log.warn("Kafka event handling failed; eventId={}, partition={}, offset={}, failureType={}",
                    event.eventId(), record.partition(), record.offset(), failure.getClass().getName());
            throw failure;
        }
    }
}
