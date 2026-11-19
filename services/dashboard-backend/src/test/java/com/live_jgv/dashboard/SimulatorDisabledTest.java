package com.live_jgv.dashboard;
import com.live_jgv.dashboard.infrastructure.jdbc.ScoreProjection;
import com.live_jgv.dashboard.infrastructure.http.ProducerGateway;
import com.live_jgv.dashboard.infrastructure.web.DashboardController;

import com.live_jgv.dashboard.application.port.in.GetMatchSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=DashboardController.class,properties="dashboard.web.allowed-origins=http://localhost:5173")
@ActiveProfiles("prod")
class SimulatorDisabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean ScoreProjection scores;
    @MockitoBean ProducerGateway producer;
    @MockitoBean GetMatchSnapshot snapshots;

    @Test void disabledByDefaultIncludingRequestsWithoutAnOrigin() throws Exception {
        mvc.perform(post("/api/matches/m/events").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(scores, producer);
    }

    @Test void sameOriginDoesNotAuthorizeWritingAndReadsRemainAvailable() throws Exception {
        mvc.perform(post("/api/matches/m/events").header("Origin", "http://localhost")
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/simulator/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.write_enabled").value(false));
        when(scores.matches()).thenReturn(java.util.List.of());
        mvc.perform(get("/api/matches")).andExpect(status().isOk());
        verifyNoInteractions(producer);
    }
}
