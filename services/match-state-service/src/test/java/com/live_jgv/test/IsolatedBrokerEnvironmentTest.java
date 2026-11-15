package com.live_jgv.test;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedBrokerEnvironmentTest {
    static Map<String,String> brokers() {
        var env=new HashMap<String,String>();
        env.put("AUDIT_RUN_ID","012345abcdef");env.put("AUDIT_PROJECT","live-jvg-9f-012345abcdef");
        env.put("AUDIT_DOCKER_CONTEXT","desktop-linux");env.put("AUDIT_DOCKER_ENDPOINT","npipe:////./pipe/dockerDesktopLinuxEngine");
        env.put("TEST_KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:29092");env.put("TEST_RABBIT_HOST","127.0.0.1");env.put("TEST_RABBIT_PORT","25672");
        env.put("TEST_RABBIT_USERNAME","jvg9f_012345abcdef");env.put("TEST_RABBIT_PASSWORD","fixture-private");env.put("TEST_RABBIT_VHOST","9f_012345abcdef");
        env.put("MATCH_STATE_TEST_POSTGRES_CONTAINER","live-jvg-9f-012345abcdef-postgres");
        env.put("MATCH_STATE_BROKER_DB_URL","jdbc:postgresql://127.0.0.1:55449/live_jvg_test_012345abcdef_brokers");
        env.put("LIVE_JVG_OUTAGE_APPROVED","true");return env;
    }
    @Test void endpointsAreExplicitAlternateAndDoNotExposeCredentials() {
        var env=brokers();var config=IsolatedBrokerEnvironment.from(env);
        assertEquals("127.0.0.1:29092",config.kafka());assertEquals(25672,config.rabbitPort());
        assertFalse(config.toString().contains("fixture-private"));
        for(String key:List.of("TEST_KAFKA_BOOTSTRAP_SERVERS","TEST_RABBIT_HOST","TEST_RABBIT_PORT","TEST_RABBIT_USERNAME","TEST_RABBIT_PASSWORD","TEST_RABBIT_VHOST","AUDIT_DOCKER_CONTEXT")) {
            var missing=new HashMap<>(env);missing.remove(key);
            assertThrows(ExtensionConfigurationException.class,()->IsolatedBrokerEnvironment.from(missing),key);
        }
        env.put("TEST_KAFKA_BOOTSTRAP_SERVERS","localhost:9092");
        assertThrows(ExtensionConfigurationException.class,()->IsolatedBrokerEnvironment.from(env));
    }
    @Test void outageRequiresApprovalBeforeEvenInspectingDocker() {
        var env=brokers();env.put("LIVE_JVG_OUTAGE_APPROVED","false");
        assertThrows(ExtensionConfigurationException.class,()->IsolatedBrokerEnvironment.from(env).docker("stop",args->{fail("No process permitted");return "";}));
    }
    @Test void ownershipAndPortMustMatchBeforeStopOrStart() throws Exception {
        var config=IsolatedBrokerEnvironment.from(brokers());
        for(boolean foreign:List.of(true,false)) {
            var calls=new ArrayList<List<String>>();
            IsolatedBrokerEnvironment.Commands commands=args->{
                calls.add(args);
                if(args.contains("context"))return "\"npipe:////./pipe/dockerDesktopLinuxEngine\"";
                if(args.contains("inspect"))return "{\"com.docker.compose.project\":\""+(foreign?"other-project":config.project())+"\",\"com.docker.compose.service\":\"postgres\",\"live-jvg.audit.run\":\"012345abcdef\"}";
                if(args.contains("port"))return "127.0.0.1:55449";
                return "stopped";
            };
            if(foreign)assertThrows(ExtensionConfigurationException.class,()->config.docker("stop",commands));
            else config.docker("stop",commands);
            assertEquals(!foreign,calls.stream().anyMatch(args->args.contains("stop")));
            assertTrue(calls.stream().filter(args->!args.contains("context")).allMatch(args->args.contains("--context")));
        }
    }
    @Test void mismatchedPortAndContextCannotTouchContainer() {
        var config=IsolatedBrokerEnvironment.from(brokers());
        assertThrows(ExtensionConfigurationException.class,()->config.docker("stop",args->"\"unix:///other.sock\""));
    }
}
