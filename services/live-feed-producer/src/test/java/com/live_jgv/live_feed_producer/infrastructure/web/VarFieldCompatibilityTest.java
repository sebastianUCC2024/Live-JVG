package com.live_jgv.live_feed_producer.infrastructure.web;
import com.live_jgv.live_feed_producer.application.service.MatchSimulationService;
import com.live_jgv.live_feed_producer.application.port.out.MatchEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers=MatchSimulationController.class,properties={"simulator.write-enabled=true","MATCH_ID=m"})
@Import(com.live_jgv.live_feed_producer.infrastructure.config.SimulationConfiguration.class)
class VarFieldCompatibilityTest {
    @Autowired MockMvc mvc;
    @MockitoBean MatchEventPublisher publisher;
    private static final String PREFIX="{\"event_id\":\"annul\",\"event_type\":\"GOAL_ANNULLED\",\"minute\":10,\"timestamp\":\"2026-11-15T00:00:00Z\",";
    @ParameterizedTest @ValueSource(strings={"\"reference_event_id\":\"goal\"","\"annuls_event_id\":\"goal\"","\"reference_event_id\":\"goal\",\"annuls_event_id\":\"goal\"","\"annuls_event_id\":\"goal\",\"reference_event_id\":\"goal\"","\"reference_event_id\":null,\"annuls_event_id\":\"goal\""})
    void acceptsAliasesButKeepsResponseAndPublicationLegacy(String refs) throws Exception {
        when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
        var request=mvc.perform(post("/api/matches/m/events").contentType("application/json").content(PREFIX+refs+"}")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isAccepted()).andExpect(jsonPath("$.reference_event_id").value("goal")).andExpect(jsonPath("$.annuls_event_id").doesNotExist());
        verify(publisher).publish(argThat(e->e.referenceEventId().equals("goal")));
    }
    @ParameterizedTest @ValueSource(strings={"\"annuls_event_id\":null","\"annuls_event_id\":1","\"annuls_event_id\":true","\"annuls_event_id\":{}","\"annuls_event_id\":[]","\"annuls_event_id\":\"\"","\"annuls_event_id\":\" \"","\"reference_event_id\":\"goal\",\"annuls_event_id\":\"other\"","\"annuls_event_id\":\"goal\",\"annuls_event_id\":\"other\""})
    void rejectsInvalidReferencesBeforePublication(String refs) throws Exception {
        mvc.perform(post("/api/matches/m/events").contentType("application/json").content(PREFIX+refs+"}")).andExpect(status().isBadRequest());verifyNoInteractions(publisher);
    }
    @Test void continuationCannotUseGoalAnnulmentAlias() throws Exception {
        mvc.perform(post("/api/matches/m/events").contentType("application/json").content((PREFIX+"\"annuls_event_id\":\"goal\"}").replace("GOAL_ANNULLED","MATCH_CONTINUES"))).andExpect(status().isBadRequest());verifyNoInteractions(publisher);
    }
}
