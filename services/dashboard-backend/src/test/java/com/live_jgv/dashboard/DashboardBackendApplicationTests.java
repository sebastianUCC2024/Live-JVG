package com.live_jgv.dashboard;
import com.live_jgv.dashboard.infrastructure.jdbc.ScoreProjection;
import com.live_jgv.dashboard.infrastructure.http.ProducerGateway;
import com.live_jgv.dashboard.infrastructure.web.DashboardController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers=DashboardController.class,properties="simulator.write-enabled=true")
class DashboardBackendApplicationTests{
 @Test void enabledPublicationPreservesClientIdentityAndContent()throws Exception{
  var body=Map.<String,Object>of("event_id","stable-id","timestamp","2026-11-15T00:00:00Z","event_type","GOAL","team_id","h","minute",10);
  when(scores.view("m")).thenReturn(Map.of("home_team_id","h","away_team_id","a"));doReturn(body).when(producer).publish("m",body);
  mvc.perform(post("/api/matches/m/events").contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
   .andExpect(status().isAccepted()).andExpect(jsonPath("$.event_id").value("stable-id"));
  verify(producer).publish("m",body);
  mvc.perform(get("/api/simulator/config")).andExpect(jsonPath("$.write_enabled").value(true));
 }
 @Test void lookupConfirmsOldOperationWithoutPublishingAgain()throws Exception{
  when(scores.operationProcessed("m","old-annulment")).thenReturn(true);
  mvc.perform(get("/api/matches/m/events/old-annulment")).andExpect(status().isOk()).andExpect(jsonPath("$.processed").value(true));
  verifyNoInteractions(producer);
 }
 @Autowired MockMvc mvc;@MockitoBean ScoreProjection scores;@MockitoBean ProducerGateway producer;@MockitoBean com.live_jgv.dashboard.application.port.in.GetMatchSnapshot snapshots;
 @Test void returnsRealProjectionThroughApi()throws Exception{when(scores.view("m")).thenReturn(Map.of("match_id","m","home_score",2));mvc.perform(get("/api/matches/m")).andExpect(status().isOk()).andExpect(jsonPath("$.home_score").value(2));}
 @Test void rejectsGoalForForeignTeamBeforePublishing()throws Exception{when(scores.view("m")).thenReturn(Map.of("home_team_id","h","away_team_id","a"));mvc.perform(post("/api/matches/m/events").contentType("application/json").content("{\"event_type\":\"GOAL\",\"team_id\":\"other\"}")).andExpect(status().isBadRequest());verifyNoInteractions(producer);}
 @Test void refusesUnknownMatch()throws Exception{when(scores.view("absent")).thenThrow(new NoSuchElementException("Partido no encontrado"));mvc.perform(get("/api/matches/absent")).andExpect(status().isNotFound());}
}
