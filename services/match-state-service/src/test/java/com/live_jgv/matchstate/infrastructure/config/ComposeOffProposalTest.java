package com.live_jgv.matchstate.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Parses YAML offline. Checks inherited environment and explicit Compose reset tags, not Docker. */
class ComposeOffProposalTest {
    private record Tagged(String tag, Object value) { }

    private static class ComposeConstructor extends SafeConstructor {
        ComposeConstructor() {
            super(new LoaderOptions());
            for (var tag : List.of("!reset", "!override")) {
                yamlConstructors.put(new Tag(tag), new AbstractConstruct() {
                    public Object construct(Node node) {
                        Object value = node instanceof MappingNode mapping ? constructMapping(mapping)
                                : node instanceof SequenceNode sequence ? constructSequence(sequence)
                                : constructScalar((ScalarNode) node);
                        return new Tagged(tag, value);
                    }
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    private static Path root() {
        var path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("docker-compose.yml"))) path = path.getParent();
        return Objects.requireNonNull(path, "Repository root");
    }

    private static Map<String, Object> read(String name) throws Exception {
        return map(new Yaml(new ComposeConstructor()).load(Files.readString(root().resolve(name))));
    }

    @Test
    void overlayOverridesInheritedOnFlagsAndCutsEveryStartupDependency() throws Exception {
        var base = map(read("docker-compose.yml").get("services"));
        var overlay = map(read("scripts/e2e/compose.manifest-proposed.yml").get("services"));
        for (var name : List.of("producer", "dashboard", "match-state", "archiver", "suspension", "worker", "notification")) {
            var proposed = map(overlay.get(name));
            var reset = assertInstanceOf(Tagged.class, proposed.get("depends_on"));
            assertEquals("!reset", reset.tag());
            assertEquals(Map.of(), reset.value());
            var env = new HashMap<>(map(map(base.get(name)).get("environment")));
            env.putAll(map(proposed.get("environment")));
            for (var key : List.of("SPRING_FLYWAY_ENABLED", "SPRING_RABBITMQ_DYNAMIC", "SPRING_KAFKA_ADMIN_AUTO_CREATE",
                    "SPRING_KAFKA_LISTENER_AUTO_STARTUP", "SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP",
                    "SPRING_RABBITMQ_LISTENER_DIRECT_AUTO_STARTUP")) assertEquals("false", env.get(key), name + ":" + key);
            assertEquals("never", env.get("SPRING_SQL_INIT_MODE"));
            if (List.of("producer", "dashboard").contains(name)) {
                assertEquals("true", map(map(base.get(name)).get("environment")).get("SIMULATOR_WRITE_ENABLED"));
                assertEquals("false", env.get("SIMULATOR_WRITE_ENABLED"));
            }
            if (name.equals("dashboard")) {
                assertDashboardPassive(env);
                assertEquals("false", env.get("DASHBOARD_RABBIT_LISTENER_ENABLED"));
            }
            if (name.equals("match-state")) {
                for (var key : List.of("KAFKA_LISTENER_AUTO_STARTUP", "MATCH_STATE_CATALOG_BOOTSTRAP_ENABLED",
                        "MATCH_STATE_RECOVERY_ENABLED", "MATCH_STATE_PUBLICATION_ENABLED")) assertEquals("false", env.get(key));
            }
        }
        var profiles = assertInstanceOf(Tagged.class, map(overlay.get("kafka-init")).get("profiles"));
        assertEquals("!override", profiles.tag());
        assertEquals(List.of("provisioning-explicit"), profiles.value());
        assertTrue(map(map(base.get("match-state")).get("depends_on")).containsKey("kafka-init"));
    }

    private static void assertDashboardPassive(Map<String, Object> env) {
        assertEquals("passive", env.get("SPRING_PROFILES_ACTIVE"));
        assertEquals("false", env.get("DASHBOARD_CATALOG_BOOTSTRAP_ENABLED"));
    }

    @Test
    void dashboardRejectsMissingOrUnsafePassiveControls() throws Exception {
        var services = map(read("scripts/e2e/compose.manifest-proposed.yml").get("services"));
        var safe = map(map(services.get("dashboard")).get("environment"));
        assertDashboardPassive(safe);
        for (var key : List.of("SPRING_PROFILES_ACTIVE", "DASHBOARD_CATALOG_BOOTSTRAP_ENABLED")) {
            var missing = new HashMap<>(safe);
            missing.remove(key);
            assertThrows(AssertionError.class, () -> assertDashboardPassive(missing), key);
            for (var unsafe : List.of("", "true", "default", "passive,prod")) {
                var changed = new HashMap<>(safe);
                changed.put(key, unsafe);
                assertThrows(AssertionError.class, () -> assertDashboardPassive(changed), key + ":" + unsafe);
            }
        }
    }
    @Test
    void academicResourcesBuildTargetsAndReplicaIsolation() throws Exception {
        var base = map(read("docker-compose.yml").get("services"));
        var overlay = map(read("scripts/e2e/compose.manifest-proposed.yml").get("services"));
        var modules = Map.of("producer", "live-feed-producer", "dashboard", "dashboard-backend",
                "match-state", "match-state-service", "archiver", "historical-archiver",
                "suspension", "betting-suspension-service", "worker", "betting-worker",
                "notification", "notification-backend");
        for (var name : List.of("producer", "dashboard", "match-state", "archiver", "suspension",
                "worker", "notification", "postgres", "kafka", "rabbitmq", "kafka-init")) {
            var service = map(base.get(name));
            var limits = map(map(map(service.get("deploy")).get("resources")).get("limits"));
            assertTrue(Double.parseDouble(limits.get("cpus").toString()) > 0);
            assertTrue(limits.get("memory").toString().matches("[1-9][0-9]*M"));
            if (modules.containsKey(name)) {
                var args = map(map(service.get("build")).get("args"));
                assertEquals(modules.get(name), args.get("SERVICE_MODULE"));
                assertTrue(args.get("JAR_FILE").toString().endsWith("-SNAPSHOT.jar"));
                var env = map(service.get("environment"));
                assertFalse(env.containsKey("JAVA_OPTS"));
                assertFalse(env.containsKey("JAVA_TOOL_OPTIONS"));
            }
        }
        assertEquals(2, map(map(base.get("worker")).get("deploy")).get("replicas"));
        assertEquals(1, map(map(base.get("dashboard")).get("deploy")).get("replicas"));
        assertEquals(2, map(map(overlay.get("worker")).get("deploy")).get("replicas"));
        var dockerfile = Files.readString(root().resolve("Dockerfile"));
        assertTrue(dockerfile.contains("FROM maven:3.9.6-eclipse-temurin-17-alpine AS build"));
        assertTrue(dockerfile.contains("FROM eclipse-temurin:17-jre-alpine AS runtime-base"));
        assertTrue(dockerfile.contains("dependency:go-offline"));
        assertTrue(dockerfile.indexOf("dependency:go-offline") < dockerfile.indexOf("COPY . /build"));
        assertTrue(dockerfile.contains("services/${SERVICE_MODULE}/pom.xml verify"));
        assertFalse(dockerfile.contains("skipTests"));
        assertTrue(dockerfile.contains("USER 10001:10001"));
        assertTrue(dockerfile.contains("FROM runtime-base AS prebuilt"));
        assertTrue(dockerfile.contains("COPY --from=build"));
    }

}
