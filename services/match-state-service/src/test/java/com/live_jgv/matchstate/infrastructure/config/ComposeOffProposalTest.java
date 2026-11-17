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
            if (name.equals("dashboard")) assertEquals("false", env.get("DASHBOARD_RABBIT_LISTENER_ENABLED"));
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
}
