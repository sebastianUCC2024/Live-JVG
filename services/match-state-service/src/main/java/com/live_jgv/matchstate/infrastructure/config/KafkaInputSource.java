package com.live_jgv.matchstate.infrastructure.config;

import org.apache.kafka.clients.admin.AdminClient;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** El UUID distingue el topic si se elimina y vuelve a crearse. */
public final class KafkaInputSource implements AutoCloseable {
    private final AdminClient admin;
    private final String namespace;
    public KafkaInputSource(AdminClient admin, String namespace) { this.admin = admin; this.namespace = namespace; }
    public String identity(String topic) {
        try {
            var cluster = admin.describeCluster().clusterId().get(5, TimeUnit.SECONDS);
            var description = admin.describeTopics(List.of(topic)).allTopicNames().get(5, TimeUnit.SECONDS).get(topic);
            if (cluster == null || cluster.isBlank() || description.topicId().equals(org.apache.kafka.common.Uuid.ZERO_UUID)) {
                throw new IllegalStateException("Kafka must expose cluster and topic UUID for durable custody");
            }
            return namespace + ":" + cluster + ":" + description.topicId();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Input source lookup interrupted", failure);
        } catch (Exception failure) { throw new IllegalStateException("Cannot establish durable input source identity", failure); }
    }
    @Override public void close() { admin.close(java.time.Duration.ofSeconds(5)); }
}
