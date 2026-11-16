package com.live_jgv.worker.entrypoint;

import com.live_jgv.worker.application.*;
import com.live_jgv.worker.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.Instant;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OperationController.class)
@Import(WorkerService.class)
class OperationControllerTest {
    @Autowired MockMvc mvc;@MockitoBean WorkerStore store;@MockitoBean CommandDecoder decoder;
    @Test void returnsDurableIdentityStateReasonAndVersionWithoutUserOrEvidence()throws Exception{
        var op=Operation.received(new SimulatedCommand("c","o","m","private-user",Instant.EPOCH),Instant.EPOCH).finish(Operation.State.BLOCKED,"SUSPENDED",Instant.EPOCH);
        when(store.find("o")).thenReturn(Optional.of(op));
        mvc.perform(get("/api/betting/operations/o")).andExpect(status().isOk())
            .andExpect(jsonPath("$.operation_id").value("o")).andExpect(jsonPath("$.state").value("BLOCKED"))
            .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.reason").value("SUSPENDED"))
            .andExpect(jsonPath("$.simulated_user_id").doesNotExist()).andExpect(jsonPath("$.observed_payload").doesNotExist());
    }
    @Test void unknownOperationReturns404NeverAnInventedAcceptance()throws Exception{
        when(store.find("absent")).thenReturn(Optional.empty());mvc.perform(get("/api/betting/operations/absent")).andExpect(status().isNotFound());
    }
    @Test void storageFailureIs503WithoutInternalDetail()throws Exception{
        when(store.find("o")).thenThrow(new DataAccessResourceFailureException("test-private-error"));
        mvc.perform(get("/api/betting/operations/o")).andExpect(status().isServiceUnavailable())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-private-error"))));
    }
    @Test void noWriteApiIsExposed()throws Exception{
        mvc.perform(post("/api/betting/operations/o")).andExpect(status().isMethodNotAllowed());verifyNoInteractions(store);
    }
}
