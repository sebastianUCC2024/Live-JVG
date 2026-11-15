package com.live_jgv.matchstate.infrastructure.jdbc;

import com.live_jgv.matchstate.MatchStateServiceApplication;
import com.live_jgv.matchstate.application.service.MatchStateService;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
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
@org.junit.jupiter.api.Tag("external-integration")
@org.junit.jupiter.api.Tag("broker-integration")
@org.junit.jupiter.api.extension.ExtendWith(com.live_jgv.test.DurableIntegrationSafety.class)
class QuarantineMessagingIntegrationTest {
    private final String id = "phase7i-" + UUID.randomUUID();
    private final String topic = id + "-events", group = id + "-group", exchange = id + "-updates", queue = id + "-queue";
    private final String match = id + "-match";
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new MessagingConfiguration().messagingObjectMapper();
    private ConfigurableApplicationContext context;

    private String required(String key) { return Objects.requireNonNull(System.getenv(key), "Missing " + key); }
    private com.live_jgv.test.IsolatedBrokerEnvironment brokers() {
        return com.live_jgv.test.IsolatedBrokerEnvironment.from(System.getenv());
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(MatchStateServiceApplication.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=durable-test",
                "--spring.datasource.url=" + required("MATCH_STATE_QUARANTINE_DB_URL"),
                "--spring.datasource.username=" + required("MATCH_STATE_TEST_DB_USERNAME"),
                "--spring.datasource.password=" + required("MATCH_STATE_TEST_DB_PASSWORD"),
                "--spring.datasource.hikari.connection-timeout=1000",
                "--spring.kafka.bootstrap-servers="+brokers().kafka(), "--match-state.kafka.topic=" + topic,
                "--spring.kafka.consumer.group-id=" + group, "--spring.kafka.consumer.auto-offset-reset=earliest",
                "--spring.kafka.listener.auto-startup=true", "--match-state.kafka.retry-interval=50ms", "--match-state.kafka.retry-cooldown=2s",
                "--match-state.rabbit.exchange=" + exchange,
                "--spring.rabbitmq.host="+brokers().rabbitHost(), "--spring.rabbitmq.port="+brokers().rabbitPort(),
                "--spring.rabbitmq.username="+brokers().rabbitUser(), "--spring.rabbitmq.password="+brokers().rabbitPassword(),
                "--spring.rabbitmq.virtual-host="+brokers().rabbitVhost(),
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
        assertEquals(brokers().postgres(),name);
        brokers().docker(action);
    }

    @Test
    void invalidRecordsContinueDeferredReplayIsDurableAndDatabaseOutageDoesNotCommit() throws Exception {
        String postgres = required("MATCH_STATE_TEST_POSTGRES_CONTAINER");
        assertEquals(brokers().postgres(),postgres);
        var cf = brokers().rabbitConnection(); var rabbit = new RabbitTemplate(cf); var ra = new RabbitAdmin(cf);
        var pf = new DefaultKafkaProducerFactory<String, String>(Map.of("bootstrap.servers", brokers().kafka(),
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer", "value.serializer", "org.apache.kafka.common.serialization.StringSerializer", "acks", "all"));
        var kafka = new KafkaTemplate<String, String>(pf); boolean stopped = false;
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", brokers().kafka()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short)1))).all().get(10, TimeUnit.SECONDS);
            var ex = new TopicExchange(exchange, true, false); ra.declareExchange(ex);
            ra.declareQueue(new org.springframework.amqp.core.Queue(queue, true, false, false));
            ra.declareBinding(BindingBuilder.bind(new org.springframework.amqp.core.Queue(queue)).to(ex).with("score." + match));
            context = start(); assigned();
            var headers = new org.apache.kafka.common.header.internals.RecordHeaders();
            headers.add("trace", null); headers.add("trace", new byte[]{0,1,(byte)255});
            kafka.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, null, match, "broken-json", headers)).get(10, TimeUnit.SECONDS);
            await(() -> committed(admin) == 1, "malformed quarantined and offset 1 committed");
            kafka.send(topic, match, "{}").get(10, TimeUnit.SECONDS); await(() -> committed(admin)==2, "missing fields quarantined");
            var goal = event("goal", EventType.GOAL, "home", null);
            kafka.send(topic, match, mapper.writeValueAsString(goal).replace("\"GOAL\"", "\"UNKNOWN\"")).get(10, TimeUnit.SECONDS);
            await(() -> committed(admin)==3, "unknown enum quarantined");
            send(kafka, admin, event("foreign", EventType.GOAL, "other", null), 4);
            var annul = event("annul", EventType.GOAL_ANNULLED, null, goal.eventId());
            send(kafka, admin, annul, 5); send(kafka, admin, annul, 6);
            var sql = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            UUID deferred = sql.queryForObject("SELECT case_id FROM match_state.event_identity WHERE match_id=? AND event_id=?", UUID.class, match, annul.eventId());
            assertEquals(5, sql.queryForObject("SELECT count(DISTINCT case_id) FROM match_state.input_receipt WHERE topic=?", Integer.class, topic));
            assertEquals(0, ra.getQueueInfo(queue).getMessageCount());
            closeContext(); context = start(); assigned(); sql = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            assertEquals("DEFERRED", sql.queryForObject("SELECT review_state FROM match_state.quarantine_case WHERE case_id=?", String.class, deferred));
            assertEquals(new Score(0,0), store().findById(match).orElseThrow().score());
            System.out.println("7I PASS restart preserves five cases and deferred identity; zero false Rabbit updates");
            send(kafka, admin, goal, 7); assertEquals(1, ra.getQueueInfo(queue).getMessageCount());
            var ingress = context.getBean(DurableEventIngress.class);
            var stale = new ReplayRequest(UUID.randomUUID(), deferred, "test-operator", "reviewed reference", 0);
            assertEquals("REVIEW_REQUIRED", ingress.replay(stale));
            var approved = new ReplayRequest(UUID.randomUUID(), deferred, "test-operator", "reviewed at revision 1", 1);
            assertEquals("APPLIED", ingress.replay(approved)); assertEquals("APPLIED", ingress.replay(approved));
            assertEquals("ALREADY_APPLIED", ingress.replay(new ReplayRequest(UUID.randomUUID(), deferred, "test-operator", "repeated review", 2)));
            assertEquals(2, ra.getQueueInfo(queue).getMessageCount()); assertEquals(new Score(0,0), store().findById(match).orElseThrow().score());
            UUID malformed = sql.queryForObject("SELECT case_id FROM match_state.input_receipt WHERE topic=? AND record_offset=0", UUID.class, topic);
            assertEquals("REJECTED", ingress.replay(new ReplayRequest(UUID.randomUUID(), malformed, "test-operator", "cannot repair JSON through replay", 2)));
            System.out.println("7I PASS controlled replay APPLIED, repeated replay no extra output, stale approval requires review, malformed replay rejected");
            send(kafka, admin, annul, 8); send(kafka, admin, event("away", EventType.GOAL, "away", null), 9);
            assertEquals(3, ra.getQueueInfo(queue).getMessageCount());
            docker("stop", postgres); stopped=true;
            kafka.send(topic, match, "invalid-during-database-outage").get(10, TimeUnit.SECONDS);
            await(() -> context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().stream().anyMatch(c -> c.isPauseRequested()), "bounded retry budget pauses consumer while database is unavailable");
            assertEquals(9, committed(admin)); assertEquals(3, ra.getQueueInfo(queue).getMessageCount());
            System.out.println("7I PASS PostgreSQL down: invalid record remains uncommitted at offset 9; no false output");
            docker("start", postgres); stopped=false;
            await(() -> committed(admin)==10, "PostgreSQL restored: custody persisted before offset 10");
            send(kafka, admin, event("after-outage", EventType.GOAL, "home", null), 11);
            assertEquals(new Score(1,1), store().findById(match).orElseThrow().score());
            assertEquals(4, ra.getQueueInfo(queue).getMessageCount());
            sql = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            assertEquals(11, sql.queryForObject("SELECT count(*) FROM match_state.input_receipt WHERE topic=?", Integer.class, topic));
            assertEquals(6, sql.queryForObject("SELECT count(DISTINCT case_id) FROM match_state.input_receipt WHERE topic=?", Integer.class, topic));
            assertArrayEquals("broken-json".getBytes(StandardCharsets.UTF_8), sql.queryForObject("SELECT raw_value FROM match_state.input_receipt WHERE topic=? AND record_offset=0", byte[].class, topic));
            assertTrue(sql.queryForObject("SELECT original_headers FROM match_state.input_receipt WHERE topic=? AND record_offset=0", String.class, topic).contains("AAH/"));
            assertEquals(4, store().load(match).orElseThrow().revision()); assertTrue(store().pendingForMatch(match).isEmpty());
            System.out.println("7I PASS final offset 11, receipts 11, cases 6, revision 4, score 1-1, Rabbit outputs 4");
            List<byte[]> bodies = rabbit.execute(channel -> {
                List<byte[]> data = new ArrayList<>(); long tag=0;
                try { for(int i=0;i<4;i++){var response=channel.basicGet(queue,false);assertNotNull(response);data.add(response.getBody());tag=response.getEnvelope().getDeliveryTag();} return data; }
                finally {if(tag!=0)channel.basicNack(tag,true,true);}
            });
            assertNotNull(bodies);var seen=new HashSet<String>();
            for(var body:bodies){var json=mapper.readTree(body);assertEquals(match,json.get("match_id").asText());assertTrue(seen.add(json.get("event_id").asText()));System.out.println("7I MESSAGE "+new String(body,StandardCharsets.UTF_8));}
            System.out.println("7I RESOURCES "+topic+" "+group+" "+queue+" "+exchange+" "+match+" deferred="+deferred);
        } finally {if(stopped)docker("start",postgres);closeContext();pf.destroy();cf.destroy();}
    }
}
