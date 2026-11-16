package com.live_jgv.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import com.live_jgv.worker.application.SuspensionEvidencePort;
import com.live_jgv.worker.entrypoint.WorkerRabbitListener;
import com.live_jgv.worker.domain.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties={"management.endpoint.health.group.readiness.include=readinessState,messagingReadiness","spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
class BettingWorkerApplicationTests {
    @MockitoBean JdbcTemplate jdbc;@MockitoBean PlatformTransactionManager tx;
    @Autowired ApplicationContext context;@Autowired RabbitListenerEndpointRegistry registry;
    @Autowired SuspensionEvidencePort evidence;@Autowired AcceptancePolicy policy;
    @Autowired org.springframework.core.env.Environment environment;
    @Test void externalIntegrationsAreDisabledAndDefaultEvidenceIsUnknown(){
        assertTrue(context.getBeansOfType(WorkerRabbitListener.class).isEmpty());
        assertTrue(registry.getListenerContainers().isEmpty());
        assertEquals("false",environment.getProperty("spring.flyway.enabled"));
        assertEquals("false",environment.getProperty("worker.acceptance.enabled"));
        assertEquals("disabled",environment.getProperty("worker.authorization.mode"));
        assertEquals(MarketEvidence.Status.UNKNOWN,evidence.fetch("m").status());
    }
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @Test void inactiveTransportIsNotReadyWhileProcessRemainsLive() throws Exception {
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health/liveness"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health/readiness"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
    }
}
