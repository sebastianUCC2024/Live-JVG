package com.live_jgv.betting.entrypoint;

import com.live_jgv.betting.application.*;
import com.live_jgv.betting.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.Instant;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StatusController.class)
@Import(SuspensionService.class)
class StatusControllerTest {
    @Autowired MockMvc mvc;@MockitoBean SuspensionStore store;@MockitoBean EventDecoder decoder;
    @Test void absentMatchIsUnknownAndNeverOpen() throws Exception {
        when(store.status("m")).thenReturn(MarketState.unknown("m"));
        mvc.perform(get("/api/betting/matches/m/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UNKNOWN")).andExpect(jsonPath("$.bets_allowed").value(false)).andExpect(jsonPath("$.reliable").value(false));
    }
    @Test void reliablePersistedOpenIsReturnedWithVersion() throws Exception {
        when(store.status("m")).thenReturn(new MarketState("m",MarketState.Status.OPEN,3,"continue",Instant.EPOCH,false,Map.of()));
        mvc.perform(get("/api/betting/matches/m/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.bets_allowed").value(true));
    }
    @Test void postgresFailureReturns503AndUnknownWithoutSensitiveDetail() throws Exception {
        when(store.status("m")).thenThrow(new DataAccessResourceFailureException("test-private-error"));
        mvc.perform(get("/api/betting/matches/m/status")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("UNKNOWN")).andExpect(jsonPath("$.bets_allowed").value(false))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-private-error"))));
    }
    @Test void inconsistentStoredStateFailsClosed() throws Exception {
        when(store.status("m")).thenThrow(new IllegalStateException("corrupt"));
        mvc.perform(get("/api/betting/matches/m/status")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("UNKNOWN"));
    }
    @Test void noPublicReviewClosureOperationExists() throws Exception {
        mvc.perform(post("/api/betting/matches/m/status")).andExpect(status().isMethodNotAllowed());verifyNoInteractions(store);
    }
}
