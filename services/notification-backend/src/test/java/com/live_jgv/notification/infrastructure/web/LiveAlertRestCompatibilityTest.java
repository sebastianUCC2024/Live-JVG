package com.live_jgv.notification.infrastructure.web;
import com.live_jgv.notification.LiveAlertContractTest;
import com.live_jgv.notification.application.NotificationStore;
import com.live_jgv.notification.domain.LiveAlert;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.nio.file.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"notification.storage.enabled=false","notification.messaging.enabled=false","spring.flyway.enabled=false","spring.rabbitmq.dynamic=false","logging.level.root=WARN"})
class LiveAlertRestCompatibilityTest {
    @Autowired WebApplicationContext context;
    @MockitoBean NotificationStore store;
    @Test void incomingWireIdentityAndSnapshotPreservePublicRestDto() throws Exception {
        var wire=LiveAlertContractTest.wire();
        var value=new LiveAlert(wire.alertId(),wire.snapshot(),wire.timestamp(),new LiveAlert.Content("es","Gol",wire.message()));
        when(store.query("match",null,50)).thenReturn(new NotificationStore.AlertPage(List.of(value),"opaque",false));
        var result=MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/alerts").param("match_id","match"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].alert_id").value(wire.alertId().toString()))
            .andExpect(jsonPath("$.items[0].alert_type").value("GOAL"))
            .andExpect(jsonPath("$.items[0].home_score").value(1))
            .andExpect(jsonPath("$.items[0].var_status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.items[0].source_updated_at").value(wire.timestamp().toString()))
            .andExpect(jsonPath("$.items[0].snapshot").doesNotExist()).andReturn();
        Files.write(Path.of("target","live-alert-rest-fixture.json"),result.getResponse().getContentAsByteArray());
    }
}
