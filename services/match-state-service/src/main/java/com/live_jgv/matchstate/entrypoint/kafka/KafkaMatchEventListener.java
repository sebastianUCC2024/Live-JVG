package com.live_jgv.matchstate.entrypoint.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.matchstate.domain.model.MatchEvent;
import com.live_jgv.matchstate.domain.port.ProcessMatchEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

public final class KafkaMatchEventListener {
    private static final Logger log = LoggerFactory.getLogger(KafkaMatchEventListener.class);
    private final ObjectMapper mapper;
    private final ProcessMatchEvent processor;

    public KafkaMatchEventListener(ObjectMapper mapper, ProcessMatchEvent processor) {
        this.mapper = mapper;
        this.processor = processor;
    }

    @KafkaListener(id = "matchEventsListener", topics = "${match-state.kafka.topic:match_events}",
            groupId = "${spring.kafka.consumer.group-id:match-state-service}",
            containerFactory = "matchEventsContainerFactory")
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
