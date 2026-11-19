package com.live_jgv.dashboard;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;

class LocalMvpConfigurationTest {
    @Test @SuppressWarnings("unchecked")
    void composeExplicitlyEnablesBothWritersAndOnlyPublishesLoopback() throws Exception {
        var compose=(Map<String,Object>)new Yaml().load(Files.readString(Path.of("..","..","docker-compose.yml")));
        var services=(Map<String,Map<String,Object>>)compose.get("services");
        var dashboard = services.get("dashboard");
        assertEquals("live-jvg-dev1-isolated-dashboard", dashboard.get("container_name"));
        assertEquals(1, ((Map<String,Object>)dashboard.get("deploy")).get("replicas"));
        for(String name:List.of("producer","dashboard")) {
            var env=(Map<String,Object>)services.get(name).get("environment");
            assertEquals("${SIMULATOR_WRITE_ENABLED:-true}",env.get("SIMULATOR_WRITE_ENABLED"));
            assertEquals("8080",env.get("PORT"));
        }
        for(var service:services.values()) {
            for(var port:(List<String>)service.getOrDefault("ports",List.of()))
                assertTrue(port.startsWith("127.0.0.1:"),"Published ports must stay on loopback");
        }
    }
}
