package com.live_jgv.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.live_jgv.dashboard.infrastructure.amqp.ScoreUpdateMessageDecoder;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.tools.ToolProvider;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Compiles the actual sibling sources, not copied fixtures. No service or broker is started. */
class Dev1LiveWireCompatibilityTest {
    private static URLClassLoader sources;
    private static final String PRODUCER = "com.live_jgv.live_feed_producer.";
    private static final String STATE = "com.live_jgv.matchstate.";

    @BeforeAll static void compileActualDev1Sources() throws Exception {
        var files = new ArrayList<String>();
        Path producer = Path.of("..", "live-feed-producer", "src", "main", "java", "com", "live_jgv", "live_feed_producer");
        Path state = Path.of("..", "match-state-service", "src", "main", "java", "com", "live_jgv", "matchstate");
        for (var dir : List.of(producer.resolve("domain"), state.resolve("domain"))) {
            try (var paths = Files.walk(dir)) { paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString())); }
        }
        files.add(producer.resolve("application/service/OutgoingReferencePolicy.java").toString());
        files.add(producer.resolve("application/service/EventPreparationException.java").toString());
        files.add(producer.resolve("infrastructure/kafka/KafkaMatchEventPublisher.java").toString());
        files.add(state.resolve("application/service/MatchStateService.java").toString());
        files.add(state.resolve("infrastructure/memory/InMemoryMatchStateStore.java").toString());
        files.add(state.resolve("entrypoint/kafka/MatchEventMessage.java").toString());
        Path output = Files.createDirectories(Path.of("target", "phase9-contract-classes"));
        var arguments = new ArrayList<>(List.of("--release", "17", "-classpath",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), "-d", output.toString()));
        arguments.addAll(files);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A Java 17+ JDK is required for source-level contract verification");
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)));
        sources = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, Dev1LiveWireCompatibilityTest.class.getClassLoader());
    }

    @AfterAll static void closeSources() throws Exception { if (sources != null) sources.close(); }

    private Class<?> producer(String name) throws Exception { return sources.loadClass(PRODUCER + name); }
    private Class<?> state(String name) throws Exception { return sources.loadClass(STATE + name); }

    private Object event(String id, String type, String reference) throws Exception {
        var enumType = producer("domain.model.EventType");
        Object value = Arrays.stream(enumType.getEnumConstants()).filter(e -> e.toString().equals(type)).findFirst().orElseThrow();
        return producer("domain.model.MatchEvent").getConstructor(String.class, String.class, enumType,
                String.class, int.class, Instant.class, String.class)
                .newInstance(id, "m", value, "h", 10, Instant.parse("2026-11-15T00:00:00.123456789Z"), reference);
    }

    @Test @SuppressWarnings("unchecked")
    void realProducerSerializationAndStateOutputsAreAcceptedByDashboard() throws Exception {
        var template = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var payloads = new ArrayList<String>();
        when(template.send(eq("match_events"), eq("m"), anyString())).thenAnswer(call -> {
            String json = call.getArgument(2); payloads.add(json);
            return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>("match_events", "m", json),
                    new RecordMetadata(new TopicPartition("match_events", 0), 0, 0, 0, 0, 0)));
        });
        var mapper = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var adapter = producer("infrastructure.kafka.KafkaMatchEventPublisher")
                .getConstructor(KafkaTemplate.class, ObjectMapper.class, String.class).newInstance(template, mapper, "match_events");
        Object match = state("domain.model.MatchState").getMethod("start", String.class, String.class, String.class).invoke(null, "m", "h", "a");
        Object store = state("infrastructure.memory.InMemoryMatchStateStore").getConstructor(List.class).newInstance(List.of(match));
        Object processor = state("application.service.MatchStateService").getConstructor(state("domain.port.MatchStateRepository")).newInstance(store);
        String[] types = {"GOAL", "VAR_CHECK", "GOAL_ANNULLED", "MATCH_CONTINUES"};
        int[] scores = {1, 1, 0, 0};
        for (int i = 0; i < types.length; i++) {
            String reference = i == 1 || i == 2 ? "e0" : null;
            Object event = event("e" + i, types[i], reference);
            ((CompletableFuture<?>) adapter.getClass().getMethod("publish", producer("domain.model.MatchEvent")).invoke(adapter, event)).join();
            Object wire = mapper.readValue(payloads.get(i), state("entrypoint.kafka.MatchEventMessage"));
            Object domainEvent = wire.getClass().getMethod("toDomain").invoke(wire);
            processor.getClass().getMethod("process", state("domain.model.MatchEvent")).invoke(processor, domainEvent);
            var pending = (List<?>) store.getClass().getMethod("pendingForMatch", String.class).invoke(store, "m");
            var decoded = new ScoreUpdateMessageDecoder().decode(mapper.writeValueAsBytes(pending.get(pending.size() - 1)));
            var outgoing=mapper.readTree(payloads.get(i));
            var matchFields=new HashSet<String>();outgoing.fieldNames().forEachRemaining(matchFields::add);
            assertEquals(Set.of("event_id","match_id","event_type","team_id","minute","timestamp","reference_event_id"),matchFields);
            var score=mapper.valueToTree(pending.get(pending.size()-1));
            var scoreFields=new HashSet<String>();score.fieldNames().forEachRemaining(scoreFields::add);
            assertEquals(Set.of("match_id","home_score","away_score","event_id","event_type","updated_at","var_status","reference_event_id"),scoreFields);
            assertEquals(types[i], decoded.eventType().name());
            assertEquals(scores[i], decoded.homeScore());
            assertEquals(reference, decoded.referenceEventId());
            assertEquals("e" + i, decoded.eventId());
            assertEquals(Instant.parse("2026-11-15T00:00:00.123456789Z"),
                    mapper.readTree(payloads.get(i)).path("timestamp").traverse(mapper).readValueAs(Instant.class));
        }
        for (String reference : List.of("", " ", "\t\n")) {
            var invalid = event("bad", "GOAL", reference);
            var failed = (CompletableFuture<?>) adapter.getClass().getMethod("publish", producer("domain.model.MatchEvent")).invoke(adapter, invalid);
            assertTrue(failed.isCompletedExceptionally());
        }
        verify(template, times(4)).send(anyString(), anyString(), anyString());
    }

    @Test void historicalDomainValuesRemainReadableWithoutNormalization() throws Exception {
        var historical = event("old", "GOAL", " ");
        assertEquals(" ", historical.getClass().getMethod("referenceEventId").invoke(historical));
    }

    @Test void currentRabbitConsumerIsClosedToUnknownFieldsButAllowsAnAbsentNullableReference() {
        var decoder=new ScoreUpdateMessageDecoder();
        String valid="{\"match_id\":\"non-uuid-match\",\"event_id\":\"non-uuid-event\",\"home_score\":1,\"away_score\":0,\"event_type\":\"GOAL\",\"updated_at\":\"2026-11-15T00:00:00.123456789Z\",\"var_status\":\"CLEAR\"}";
        var update=decoder.decode(valid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("non-uuid-event",update.eventId());assertNull(update.referenceEventId());
        assertThrows(com.live_jgv.dashboard.infrastructure.amqp.InvalidScoreUpdateMessageException.class,
                ()->decoder.decode(valid.replace("}",",\"schema_version\":1}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
