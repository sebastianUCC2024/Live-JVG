package com.live_jgv.test;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import static org.junit.jupiter.api.Assertions.*;

class DurableIntegrationSafetyTest {
    private Map<String,String> approved(){return new HashMap<>(Map.of("LIVE_JVG_INTEGRATION_APPROVED","true",
            "AUDIT_RUN_ID","012345abcdef","AUDIT_PROJECT","live-jvg-9f-012345abcdef",
            "DASHBOARD_INTEGRATION_DB_URL","jdbc:postgresql://localhost:55449/live_jvg_test_012345abcdef_dashboard",
            "DASHBOARD_TEST_DB_USERNAME","jvg9f_012345abcdef","DASHBOARD_TEST_DB_PASSWORD","fixture-only"));}
    @Test void inheritedUrlAndMvpCredentialsDoNotAuthorizeSpringDatasource() {
        var env=approved();env.remove("LIVE_JVG_INTEGRATION_APPROVED");
        assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(env));
        var missing=approved();missing.remove("DASHBOARD_TEST_DB_PASSWORD");
        missing.put("DB_PASSWORD","fixture-not-an-authorization");
        assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(missing));
    }
    @Test void approvedTestDatabasePassesAndSharedRemoteOrCredentialUrlsFailWithoutEchoing() {
        var env=approved();assertDoesNotThrow(()->DurableIntegrationSafety.validate(env));
        for(String url:List.of("jdbc:postgresql://localhost/live_jvg","jdbc:postgresql://remote/live_jvg_test_dashboard",
                "jdbc:postgresql://user:do-not-leak@localhost/live_jvg_test_dashboard",
                "jdbc:postgresql://localhost/live_jvg_test_dashboard?password=do-not-leak")) {
            env.put("DASHBOARD_INTEGRATION_DB_URL",url);
            var failure=assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(env));
            assertFalse(failure.getMessage().contains("do-not-leak"));assertNull(failure.getCause());
        }
    }
    @Test void inheritedSpringOverridesAreRejectedBeforeContextCreation() {
        var env=approved();env.put("SPRING_DATASOURCE_URL","withheld");
        assertThrows(ExtensionConfigurationException.class,()->DurableIntegrationSafety.validate(env));
    }
}
