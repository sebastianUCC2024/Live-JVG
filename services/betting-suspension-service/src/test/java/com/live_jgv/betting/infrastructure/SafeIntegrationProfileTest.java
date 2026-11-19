package com.live_jgv.betting.infrastructure;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeIntegrationProfileTest {
    @Test void selectingIntegrationProfileCannotEnableSideEffects() throws Exception {
        var profile=new Properties();
        try(var in=getClass().getResourceAsStream("/application-dev3-integration.properties")){assertNotNull(in);profile.load(in);}
        assertEquals("false",profile.getProperty("spring.flyway.enabled"));
        for(String key:profile.stringPropertyNames()) if(key.endsWith(".consumer.enabled")||key.endsWith(".publication.enabled")||key.endsWith(".acceptance.enabled"))
            assertEquals("false",profile.getProperty(key),key);
        assertEquals("readinessState,db,messagingReadiness,marketOutbox",profile.getProperty("management.endpoint.health.group.readiness.include"));
        assertEquals("${DEV3_CONTROL_DB_URL}",profile.getProperty("spring.datasource.url"));
    }
    @Test void authorityProfileRequiresOneExplicitDatabaseAndSeparateCredentials() throws Exception {
        var profile=new Properties();
        try(var in=getClass().getResourceAsStream("/application-dev3-authority.properties")){assertNotNull(in);profile.load(in);}
        assertEquals("${DEV3_AUTHORITY_DB_URL}",profile.getProperty("spring.datasource.url"));
        assertEquals("${DEV3_CONTROL_DB_USERNAME}",profile.getProperty("spring.datasource.username"));
        assertEquals("${DEV3_CONTROL_DB_PASSWORD}",profile.getProperty("spring.datasource.password"));
        assertEquals("false",profile.getProperty("spring.flyway.enabled"));
        for(String key:profile.stringPropertyNames()) if(key.endsWith(".consumer.enabled")||key.endsWith(".publication.enabled")||key.endsWith(".acceptance.enabled"))
            assertEquals("false",profile.getProperty(key),key);
    }
}
