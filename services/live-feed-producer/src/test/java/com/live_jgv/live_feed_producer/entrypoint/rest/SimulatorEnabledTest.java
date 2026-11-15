package com.live_jgv.live_feed_producer.entrypoint.rest;

import com.live_jgv.live_feed_producer.application.service.MatchSimulationService;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=MatchSimulationController.class, properties={"simulator.write-enabled=true","MATCH_ID=m"})
@Import(MatchSimulationService.class)
class SimulatorEnabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean MatchEventPublisher publisher;
    private static final String JSON="""
        {"event_id":"stable-id","event_type":"GOAL","team_id":"home","minute":10,
         "timestamp":"2026-11-15T00:00:00.123456789Z","reference_event_id":null}
        """;

    @Test void enabledStillWaitsForKafkaAndReturns202WithUnchangedIdentity() throws Exception {
        var ack=new CompletableFuture<Void>();when(publisher.publish(any())).thenReturn(ack);
        var result=mvc.perform(post("/api/matches/m/events").contentType("application/json").content(JSON))
                .andExpect(request().asyncStarted()).andReturn();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->result.getAsyncResult(10));
        ack.complete(null);
        mvc.perform(asyncDispatch(result)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.event_id").value("stable-id"))
                .andExpect(jsonPath("$.timestamp").value("2026-11-15T00:00:00.123456789Z"));
    }
    @Test void kafkaFailureRemains503AndValidationRemains400() throws Exception {
        when(publisher.publish(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
        var result=mvc.perform(post("/api/matches/m/events").contentType("application/json").content(JSON)).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isServiceUnavailable());
        clearInvocations(publisher);
        mvc.perform(post("/api/matches/m/events").contentType("application/json")
                .content(JSON.replace("\"reference_event_id\":null","\"reference_event_id\":\" \"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }
}
