package com.live_jgv.notification;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import com.live_jgv.notification.infrastructure.config.WebConfiguration;

class SafetyConfigurationTest {
    @Test void packagedDefaultsNeverActivateInfrastructureOrMigrations() throws Exception {
        var properties=new Properties();
        try (var input=getClass().getResourceAsStream("/application.properties")) { properties.load(input); }
        assertEquals("false",properties.getProperty("spring.flyway.enabled"));
        assertEquals("never",properties.getProperty("spring.sql.init.mode"));
        assertEquals("false",properties.getProperty("spring.rabbitmq.dynamic"));
        assertEquals("${NOTIFICATION_STORAGE_ENABLED:false}",properties.getProperty("notification.storage.enabled"));
        assertEquals("${NOTIFICATION_MESSAGING_ENABLED:false}",properties.getProperty("notification.messaging.enabled"));
        assertFalse(properties.containsKey("spring.datasource.password"));
    }
    @Test void corsRequiresExactOriginsWithoutCredentials() {
        assertDoesNotThrow(()->new WebConfiguration(""));
        assertDoesNotThrow(()->new WebConfiguration("http://127.0.0.1:5173"));
        assertThrows(IllegalArgumentException.class,()->new WebConfiguration("*"));
        assertThrows(IllegalArgumentException.class,()->new WebConfiguration("http://user:secret@localhost"));
    }
}
