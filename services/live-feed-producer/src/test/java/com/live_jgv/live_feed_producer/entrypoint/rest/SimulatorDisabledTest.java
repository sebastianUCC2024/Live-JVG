package com.live_jgv.live_feed_producer.entrypoint.rest;

import com.live_jgv.live_feed_producer.application.service.MatchSimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MatchSimulationController.class)
@ActiveProfiles("prod")
class SimulatorDisabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean MatchSimulationService simulator;
    @Test void defaultRejectsNativeClientBeforePublication() throws Exception {
        mvc.perform(post("/api/matches/m/events").contentType("application/json").content("{}"))
                .andExpect(request().asyncStarted()).andDo(result ->
                    mvc.perform(asyncDispatch(result)).andExpect(status().isForbidden()));
        verifyNoInteractions(simulator);
    }
}
