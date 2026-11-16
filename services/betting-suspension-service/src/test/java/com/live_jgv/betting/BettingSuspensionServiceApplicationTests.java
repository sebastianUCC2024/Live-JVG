package com.live_jgv.betting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.context.ApplicationContext;
import com.live_jgv.betting.application.SuspensionPublisher;
import com.live_jgv.betting.infrastructure.OutboxDispatcher;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration","spring.kafka.admin.auto-create=false"})
class BettingSuspensionServiceApplicationTests {
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean PlatformTransactionManager transactions;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired ApplicationContext context;
    @Autowired org.springframework.core.env.Environment environment;
    @Test void contextLoadsWithoutExternalInfrastructureAndAllIntegrationsAreDisabled() {
        assertFalse(registry.getListenerContainer("bettingSuspensionListener").isRunning());
        assertEquals("false",environment.getProperty("spring.flyway.enabled"));
        assertTrue(context.getBeansOfType(SuspensionPublisher.class).isEmpty());
        assertTrue(context.getBeansOfType(OutboxDispatcher.class).isEmpty());
        assertFalse(context.getBean(com.live_jgv.betting.application.LeaseSourceVerifier.class).verify("m",1,"unverified-checkpoint"));
        assertTrue(context.getBeansOfType(com.live_jgv.betting.infrastructure.JdbcOpenLeaseIssuer.class).isEmpty());
    }
}
