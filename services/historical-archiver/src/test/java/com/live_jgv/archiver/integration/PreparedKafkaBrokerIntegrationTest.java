package com.live_jgv.archiver.integration;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only real metadata probe; does not certify delivery, offsets or E2E. */
@EnabledIfEnvironmentVariable(named="DEV3_RUN_ISOLATED_BROKER_TESTS",matches="true")
class PreparedKafkaBrokerIntegrationTest {
    @Test void approvedIsolatedTopicExistsWithoutCreatingItOrCommittingOffsets() throws Exception {
        assertEquals("I_APPROVE_ISOLATED_BROKER_PROBES",System.getenv("DEV3_BROKER_APPROVAL"));
        String bootstrap=System.getenv("DEV3_KAFKA_BOOTSTRAP_SERVERS");
        String topic=System.getenv("DEV3_MATCH_EVENTS_TOPIC");
        assertNotNull(bootstrap);assertTrue(bootstrap.matches("127\\.0\\.0\\.1:[0-9]+"));
        assertNotEquals("127.0.0.1:9092",bootstrap,"Shared/default broker excluded");
        assertNotNull(topic);assertTrue(topic.matches("dev3_it_[A-Za-z0-9_-]+"));
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",bootstrap,"request.timeout.ms",5000,
                "default.api.timeout.ms",5000))){
            var description=admin.describeTopics(java.util.List.of(topic)).allTopicNames().get(5,TimeUnit.SECONDS).get(topic);
            assertNotNull(description);assertFalse(description.partitions().isEmpty());
        }
    }
}
