package com.live_jgv.test;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import static org.junit.jupiter.api.Assertions.*;

class DurableIntegrationSafetyTest {
    private Map<String,String> approved() {
        return new HashMap<>(Map.of("LIVE_JVG_INTEGRATION_APPROVED","true",
                "AUDIT_RUN_ID","012345abcdef","AUDIT_PROJECT","live-jvg-9f-012345abcdef",
                "MATCH_STATE_TEST_DB_URL","jdbc:postgresql://127.0.0.1:55449/live_jvg_test_012345abcdef_state",
                "MATCH_STATE_TEST_DB_USERNAME","jvg9f_012345abcdef","MATCH_STATE_TEST_DB_PASSWORD","fixture-only"));
    }
    @Test void inheritedDatabaseVariableAloneNeverAuthorizesIntegration() {
        var env=approved();env.remove("LIVE_JVG_INTEGRATION_APPROVED");
        assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(env,"JdbcMatchStateStoreIntegrationTest"));
    }
    @Test void onlyExplicitLoopbackDisposableDatabaseIsAccepted() {
        var env=approved();
        assertDoesNotThrow(()->DurableIntegrationSafety.validate(env,"JdbcMatchStateStoreIntegrationTest"));
        for(String url:List.of("jdbc:postgresql://127.0.0.1/live_jvg","jdbc:postgresql://remote/live_jvg_test_state",
                "jdbc:postgresql://user:do-not-leak@localhost/live_jvg_test_state",
                "jdbc:postgresql://localhost/live_jvg_test_state?password=do-not-leak")) {
            env.put("MATCH_STATE_TEST_DB_URL",url);
            var failure=assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(env,"JdbcMatchStateStoreIntegrationTest"));
            assertFalse(failure.getMessage().contains("do-not-leak"));
            assertNull(failure.getCause());
        }
    }
    @Test void brokerOutagesRequireSeparateApprovalAndDedicatedContainer() {
        var env=approved();env.put("MATCH_STATE_BROKER_DB_URL",env.get("MATCH_STATE_TEST_DB_URL"));
        env.putAll(IsolatedBrokerEnvironmentTest.brokers());
        env.put("MATCH_STATE_TEST_BROKERS","true");
        env.remove("LIVE_JVG_OUTAGE_APPROVED");
        assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(env,"DurableMessagingIntegrationTest"));
        env.put("LIVE_JVG_OUTAGE_APPROVED","true");
        assertDoesNotThrow(()->DurableIntegrationSafety.validate(env,"DurableMessagingIntegrationTest"));
        env.put("MATCH_STATE_TEST_POSTGRES_CONTAINER","shared-postgres");
        assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(env,"DurableMessagingIntegrationTest"));
        env.put("MATCH_STATE_QUARANTINE_DB_URL",env.get("MATCH_STATE_TEST_DB_URL"));
        env.put("MATCH_STATE_TEST_QUARANTINE","true");env.put("MATCH_STATE_TEST_POSTGRES_CONTAINER","live-jvg-9f-012345abcdef-postgres");
        assertDoesNotThrow(()->DurableIntegrationSafety.validate(env,"QuarantineMessagingIntegrationTest"));
    }
    @Test void inheritedFlywayOrDifferentRunCannotRedirectIntegration() {
        var env=approved();env.put("SPRING_FLYWAY_URL","withheld");
        assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(env,"JdbcMatchStateStoreIntegrationTest"));
        env.remove("SPRING_FLYWAY_URL");env.put("AUDIT_RUN_ID","abcdef012345");
        assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(env,"JdbcMatchStateStoreIntegrationTest"));
    }
}
