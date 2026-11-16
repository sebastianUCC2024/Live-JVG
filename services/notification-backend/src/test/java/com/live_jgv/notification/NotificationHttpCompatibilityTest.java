package com.live_jgv.notification;

import com.live_jgv.notification.application.NotificationStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Boot-managed MVC/Jackson and CORS, with a mocked port and no external services. */
@SpringBootTest(properties={"notification.storage.enabled=false","notification.messaging.enabled=false",
        "spring.flyway.enabled=false","spring.rabbitmq.dynamic=false","debug=false","logging.level.root=WARN",
        "notification.web.allowed-origins=http://127.0.0.1:5173"})
class NotificationHttpCompatibilityTest {
    @Autowired WebApplicationContext context;
    @MockitoBean NotificationStore store;
    MockMvc mvc;
    @BeforeEach void setup() { mvc=MockMvcBuilders.webAppContextSetup(context).build(); }
    @Test void bootManagedJsonRetainsTheExistingSnakeCaseAndNanosecondContract() throws Exception {
        when(store.query("match-001",null,50)).thenReturn(new NotificationStore.AlertPage(List.of(Fixtures.alert()),"opaque-cursor",false));
        mvc.perform(get("/api/alerts").param("match_id","match-001"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.items[0].contract_version").value("1.0"))
            .andExpect(jsonPath("$.items[0].source_event_id").value("event-GOAL"))
            .andExpect(jsonPath("$.items[0].source_updated_at").value("2026-11-15T15:30:00.123456789Z"))
            .andExpect(jsonPath("$.items[0].content.locale").value("es"))
            .andExpect(jsonPath("$.items[0].contractVersion").doesNotExist())
            .andExpect(jsonPath("$.next_cursor").value("opaque-cursor"))
            .andExpect(jsonPath("$.has_more").value(false));
    }
    @Test void corsAcceptsOnlyTheConfiguredOriginWithoutCredentials() throws Exception {
        mvc.perform(options("/api/alerts").header("Origin","http://127.0.0.1:5173")
                .header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","Accept"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://127.0.0.1:5173"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/alerts").header("Origin","http://localhost:5173")
                .header("Access-Control-Request-Method","GET"))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(store);
    }
    @Test void databaseErrorsAreSanitizedByTheBootManagedHttpPipeline() throws Exception {
        when(store.query(anyString(),any(),anyInt())).thenThrow(new DataAccessResourceFailureException("private-connection-details"));
        mvc.perform(get("/api/alerts").param("match_id","match-001"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-connection-details"))))
            .andExpect(jsonPath("$.error").exists());
    }
    @Test void startedApplicationIsNotReadyWhenMandatoryInfrastructureIsDisabled() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
    }
}
