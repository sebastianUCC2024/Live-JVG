package com.live_jgv.matchstate.entrypoint.kafka;

import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.domain.port.ProcessMatchEvent;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Instant;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaMatchEventListenerTest {
    private ProcessMatchEvent processor;
    private KafkaMatchEventListener listener;
    private static final String VALID = """
            {"event_id":"e1","match_id":"m1","event_type":"GOAL","team_id":"home",
             "minute":42,"timestamp":"2026-11-12T15:00:00Z","reference_event_id":null}
            """;

    @BeforeEach
    void setUp() {
        processor = mock(ProcessMatchEvent.class);
        listener = new KafkaMatchEventListener(new MessagingConfiguration().messagingObjectMapper(), processor);
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void mapsSnakeCaseAndInvokesUseCaseForEachEventType(EventType type) {
        String json = VALID.replace("\"GOAL\"", "\"" + type.name() + "\"")
                .replace("\"reference_event_id\":null", "\"reference_event_id\":\"goal-1\"");
        listener.onMessage(record("m1", json));
        verify(processor).process(new MatchEvent("e1", "m1", type, "home", 42,
                Instant.parse("2026-11-12T15:00:00Z"), "goal-1"));
    }

    @Test
    void permitsOmittedOptionalFields() {
        listener.onMessage(record("m1", VALID.replace("\"GOAL\"", "\"MATCH_CONTINUES\"")
                .replace("\"team_id\":\"home\",", "").replace(",\"reference_event_id\":null", "")));
        verify(processor).process(argThat(event -> event.teamId() == null && event.referenceEventId() == null));
    }

    static Stream<String> invalidMessages() {
        return Stream.of("not-json", "null", "{}", VALID + " {}", VALID.replace("\"GOAL\"", "\"UNKNOWN\""),
                VALID.replace("\"minute\":42,", ""), VALID.replace("\"minute\":42", "\"minute\":null"),
                VALID.replace("\"minute\":42", "\"minute\":1.5"), VALID.replace("\"minute\":42", "\"minute\":\"42\""),
                VALID.replace("\"minute\":42", "\"minute\":131"), VALID.replace("\"event_id\":\"e1\"", "\"event_id\":123"),
                VALID.replace("\"event_id\":\"e1\"", "\"event_id\":\" \""),
                VALID.replace("\"event_type\":\"GOAL\"", "\"event_type\":0"),
                VALID.replace("2026-11-12T15:00:00Z", "bad-date"), VALID.replace("\"event_id\"", "\"eventId\""),
                VALID.replace("\"minute\":42", "\"minute\":42,\"minute\":43"),
                VALID.replace("\"reference_event_id\":null", "\"extra\":true"),
                VALID.replace("\"GOAL\"", "\"GOAL_ANNULLED\""));
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void rejectsInvalidInputWithoutInvokingDomain(String json) {
        assertThrows(InvalidKafkaMessageException.class, () -> listener.onMessage(record("m1", json)));
        verifyNoInteractions(processor);
    }

    @Test
    void rejectsTombstonesAndWrongOrMissingKey() {
        assertThrows(InvalidKafkaMessageException.class, () -> listener.onMessage(record("m1", null)));
        assertThrows(InvalidKafkaMessageException.class, () -> listener.onMessage(record("other", VALID)));
        assertThrows(InvalidKafkaMessageException.class, () -> listener.onMessage(record(null, VALID)));
        verifyNoInteractions(processor);
    }

    @Test
    void propagatesCriticalFailureToPreventOffsetAcknowledgement() {
        var failure = new IllegalStateException("RabbitMQ unavailable");
        when(processor.process(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> listener.onMessage(record("m1", VALID))));
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("match_events", 0, 7L, key, value);
    }
}
