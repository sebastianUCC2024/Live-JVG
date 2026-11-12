package com.live_jgv.live_feed_producer.domain.model;

import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class DomainIndependenceTest {
    @Test
    void domainBytecodeDoesNotReferenceInfrastructureLibraries() throws Exception {
        for (var type : new Class<?>[] {MatchEvent.class, EventType.class, MatchEventPublisher.class}) {
            try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                assertNotNull(stream);
                var bytecode = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String dependency : new String[] {"org/apache/kafka", "org/springframework", "com/fasterxml/jackson", "/infrastructure/"}) {
                    assertFalse(bytecode.contains(dependency), type.getName() + " depends on " + dependency);
                }
            }
        }
        assertEquals(CompletableFuture.class,
                MatchEventPublisher.class.getMethod("publish", MatchEvent.class).getReturnType());
    }
}
