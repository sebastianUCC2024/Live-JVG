package com.live_jgv.matchstate.infrastructure.jdbc;

import com.live_jgv.matchstate.MatchStateServiceApplication;
import com.live_jgv.matchstate.application.service.MatchStateService;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: dedicated PostgreSQL container/DB, new Kafka topic/group and Rabbit queue per execution. */
@EnabledIfEnvironmentVariable(named = "MATCH_STATE_TEST_BROKERS", matches = "true")
class DurableMessagingIntegrationTest {
    private final String id = "phase7h-" + UUID.randomUUID();
    private final String topic = id + "-events", group = id + "-group", exchange = id + "-updates", queue = id + "-queue";
    private final String match = id + "-match";
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new MessagingConfiguration().messagingObjectMapper();
    private ConfigurableApplicationContext context;

    private String required(String key) { return Objects.requireNonNull(System.getenv(key), "Missing " + key); }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(MatchStateServiceApplication.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=durable-test",
                "--spring.datasource.url=" + required("MATCH_STATE_BROKER_DB_URL"),
                "--spring.datasource.username=" + required("MATCH_STATE_TEST_DB_USERNAME"),
                "--spring.datasource.password=" + required("MATCH_STATE_TEST_DB_PASSWORD"),
                "--spring.datasource.hikari.connection-timeout=1000",
                "--spring.kafka.bootstrap-servers=localhost:9092", "--match-state.kafka.topic=" + topic,
                "--spring.kafka.consumer.group-id=" + group, "--spring.kafka.consumer.auto-offset-reset=earliest",
                "--spring.kafka.listener.auto-startup=true", "--match-state.kafka.retry-interval=200ms",
                "--match-state.rabbit.exchange=" + exchange,
                "--spring.rabbitmq.host=localhost", "--spring.rabbitmq.port=5672",
                "--spring.rabbitmq.username=guest", "--spring.rabbitmq.password=guest",
                "--match-state.matches[0].match-id=" + match,
                "--match-state.matches[0].home-team-id=home", "--match-state.matches[0].away-team-id=away");
    }

    private void closeContext() { if (context != null) { context.close(); context = null; } }
    private JdbcMatchStateStore store() { return context.getBean(JdbcMatchStateStore.class); }
    private MatchEvent event(String name, EventType type, String team, String reference) {
        return new MatchEvent(id + "-" + name, match, type, team, 10, Instant.now(), reference);
    }
    private void await(BooleanSupplier condition, String label) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < until) { if (condition.getAsBoolean()) { System.out.println("7H PASS " + label); return; } Thread.sleep(100); }
        fail("Timed out: " + label);
    }
    private void assigned() throws Exception {
        await(() -> context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().stream()
                .anyMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty()), "partition assigned");
    }
    private long committed(AdminClient admin) {
        try {
            var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(topic, 0)); return offset == null ? -1 : offset.offset();
        } catch (Exception failure) { return -1; }
    }
    private void send(KafkaTemplate<String, String> kafka, AdminClient admin, MatchEvent event, long offset) throws Exception {
        kafka.send(topic, match, mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        await(() -> committed(admin) == offset, "committed offset " + offset);
    }
    private void docker(String action, String name) throws Exception {
        var process = new ProcessBuilder("docker", action, name).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void recoversAcrossRestartsDatabaseOutageAndStartupPendingOutboxWithoutHistoricalRepublish() throws Exception {
        String postgres = required("MATCH_STATE_TEST_POSTGRES_CONTAINER");
        assertTrue(postgres.startsWith("live-jvg-7h-"), "Only a dedicated 7H container may be stopped");
        var cf = new CachingConnectionFactory("localhost", 5672); var rabbit = new RabbitTemplate(cf); var ra = new RabbitAdmin(cf);
        var pf = new DefaultKafkaProducerFactory<String, String>(Map.of("bootstrap.servers", "localhost:9092",
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer", "value.serializer", "org.apache.kafka.common.serialization.StringSerializer", "acks", "all"));
        var kafka = new KafkaTemplate<String, String>(pf);
        boolean stopped = false;
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", "localhost:9092"))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            var ex = new TopicExchange(exchange, true, false); ra.declareExchange(ex);
            ra.declareQueue(new org.springframework.amqp.core.Queue(queue, true, false, false));
            ra.declareBinding(BindingBuilder.bind(new org.springframework.amqp.core.Queue(queue)).to(ex).with("score." + match));
            context = start(); assigned();
            var home = event("home", EventType.GOAL, "home", null);
            send(kafka, admin, home, 1); send(kafka, admin, home, 2);
            assertEquals(1, ra.getQueueInfo(queue).getMessageCount());
            closeContext(); context = start(); assigned();
            assertEquals(new Score(1, 0), store().findById(match).orElseThrow().score());
            assertEquals(1, ra.getQueueInfo(queue).getMessageCount());
            System.out.println("7H PASS restart after GOAL retains 1-0 without republishing history");
            send(kafka, admin, home, 3); assertEquals(1, ra.getQueueInfo(queue).getMessageCount());
            var away = event("away", EventType.GOAL, "away", null); send(kafka, admin, away, 4);
            assertEquals(new Score(1, 1), store().findById(match).orElseThrow().score());
            var review = event("review", EventType.VAR_CHECK, null, away.eventId()); send(kafka, admin, review, 5);
            assertEquals(VarStatus.IN_PROGRESS, store().findById(match).orElseThrow().varStatus());
            var annul = event("annul", EventType.GOAL_ANNULLED, null, away.eventId()); send(kafka, admin, annul, 6);
            closeContext(); context = start(); assigned();
            assertEquals(new Score(1, 0), store().findById(match).orElseThrow().score());
            send(kafka, admin, annul, 7); assertEquals(4, ra.getQueueInfo(queue).getMessageCount());
            System.out.println("7H PASS restart after annulment; duplicate goal and annulment do not publish twice");
            send(kafka, admin, event("continue", EventType.MATCH_CONTINUES, null, null), 8);
            assertEquals(VarStatus.CLEAR, store().findById(match).orElseThrow().varStatus());

            // Only the new PostgreSQL container owned by this phase is stopped/restored.
            docker("stop", postgres); stopped = true;
            var duringOutage = event("during-outage", EventType.GOAL, "home", null);
            kafka.send(topic, match, mapper.writeValueAsString(duringOutage)).get(10, TimeUnit.SECONDS);
            Thread.sleep(2200);
            assertEquals(8, committed(admin)); assertEquals(5, ra.getQueueInfo(queue).getMessageCount());
            System.out.println("7H PASS PostgreSQL down: Kafka offset frozen at 8, no output");
            docker("start", postgres); stopped = false;
            await(() -> committed(admin) == 9, "PostgreSQL restored: offset 9");
            assertEquals(new Score(2, 0), store().findById(match).orElseThrow().score());
            assertEquals(6, ra.getQueueInfo(queue).getMessageCount());

            // Commit acceptance only, simulating a crash before publish, with no new Kafka record.
            var pendingEvent = event("pending-at-restart", EventType.GOAL, "away", null);
            new MatchStateService(store()).process(pendingEvent);
            var pending = store().storedPublication(store().pendingForMatch(match).get(0)).orElseThrow();
            closeContext(); context = start(); assigned();
            assertTrue(store().pendingForMatch(match).isEmpty());
            assertEquals(7, ra.getQueueInfo(queue).getMessageCount());
            assertEquals(9, committed(admin));
            assertEquals(7, store().load(match).orElseThrow().revision());
            assertEquals(new Score(2, 1), store().findById(match).orElseThrow().score());
            System.out.println("7H PASS startup drains pending without new Kafka record; revision 7, score 2-1, queue 7");

            List<byte[]> bodies = rabbit.execute(channel -> {
                List<byte[]> result = new ArrayList<>(); long tag = 0;
                try {
                    for (int i = 0; i < 7; i++) { var response = channel.basicGet(queue, false); assertNotNull(response); result.add(response.getBody()); tag = response.getEnvelope().getDeliveryTag(); }
                    return result;
                } finally { if (tag != 0) channel.basicNack(tag, true, true); }
            });
            assertNotNull(bodies); assertEquals(7, bodies.size());
            var seen = new HashSet<String>(); boolean exact = false;
            for (var body : bodies) {
                var json = mapper.readTree(body); assertEquals(match, json.get("match_id").asText());
                assertEquals(Set.of("match_id", "home_score", "away_score", "event_id", "event_type", "updated_at", "var_status", "reference_event_id"),
                        mapper.convertValue(json, Map.class).keySet());
                assertTrue(seen.add(json.get("event_id").asText()));
                if (json.get("event_id").asText().equals(pendingEvent.eventId())) { assertArrayEquals(pending.payload().getBytes(StandardCharsets.UTF_8), body); exact = true; }
                System.out.println("7H MESSAGE " + new String(body, StandardCharsets.UTF_8));
            }
            assertTrue(exact);
            System.out.println("7H RESOURCES " + topic + " " + group + " " + queue + " " + exchange + " " + match);
        } finally {
            if (stopped) docker("start", postgres);
            closeContext(); pf.destroy(); cf.destroy();
        }
    }
}
