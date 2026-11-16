package com.live_jgv.worker.infrastructure;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeIntegrationProfileTest {
    @Test void selectingIntegrationProfileCannotEnableSideEffects() throws Exception {
        var profile=new Properties();
        try(var in=getClass().getResourceAsStream("/application-dev3-integration.properties")){assertNotNull(in);profile.load(in);}
        assertEquals("false",profile.getProperty("spring.flyway.enabled"));
        assertEquals("disabled",profile.getProperty("worker.authorization.mode"));
        for(String key:profile.stringPropertyNames()) if(key.endsWith(".consumer.enabled")||key.endsWith(".publication.enabled")||key.endsWith(".acceptance.enabled"))
            assertEquals("false",profile.getProperty(key),key);
        assertEquals("readinessState,db",profile.getProperty("management.endpoint.health.group.readiness.include"));
        assertEquals("${DEV3_WORKER_DB_URL}",profile.getProperty("spring.datasource.url"));
    }
}
