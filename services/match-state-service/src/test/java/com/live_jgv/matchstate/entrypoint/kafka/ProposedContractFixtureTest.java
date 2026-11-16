package com.live_jgv.matchstate.entrypoint.kafka;

import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise real DEV 1 mapping against review fixtures, without activating a schema on ingress. */
class ProposedContractFixtureTest {
    private final Path fixtures = Path.of("docs/architecture-closure/contracts");
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new MessagingConfiguration().messagingObjectMapper();

    @Test void validMatchEventFixturesAreAcceptedByTheActualWireDto() throws Exception {
        for (String name : java.util.List.of("match-event.v1.valid.json", "match-event.v1.optional-absent.valid.json")) {
            var event = mapper.readValue(Files.readString(fixtures.resolve(name)), MatchEventMessage.class).toDomain();
            assertEquals("g1", event.eventId());
            assertEquals(EventType.GOAL, event.eventType());
            assertNull(event.referenceEventId());
        }
    }

    @Test void missingIdentityAndAnnulmentReferenceCannotReachTheDomain() {
        for (String name : java.util.List.of("match-event.v1.invalid.json", "match-event.v1.annulment-reference.invalid.json")) {
            assertThrows(IllegalArgumentException.class,
                    () -> mapper.readValue(Files.readString(fixtures.resolve(name)), MatchEventMessage.class).toDomain());
        }
    }

    @Test void scoreUpdateExampleMatchesActualOutputFieldNamesAndValues() throws Exception {
        var example = mapper.readTree(Files.readString(fixtures.resolve("score-update.v1.valid.json")));
        var update = new ScoreUpdate("m1", 1, 0, "g1", EventType.GOAL,
                Instant.parse("2026-11-16T22:00:00Z"), VarStatus.CLEAR, null);
        assertEquals(example, mapper.valueToTree(update));
    }
}
