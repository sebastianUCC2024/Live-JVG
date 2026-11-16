package com.live_jgv.archiver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "spring.kafka.admin.auto-create=false"
})
class HistoricalArchiverApplicationTests {
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean PlatformTransactionManager transactions;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired org.springframework.core.env.Environment environment;
    @Test void contextLoadsWithoutExternalInfrastructureAndConsumptionIsDisabled() {
        assertFalse(registry.getListenerContainer("historicalArchiveListener").isRunning());
        assertEquals("false",environment.getProperty("spring.flyway.enabled"));
    }
}
