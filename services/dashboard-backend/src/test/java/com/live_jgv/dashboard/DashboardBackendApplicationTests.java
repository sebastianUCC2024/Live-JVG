package com.live_jgv.dashboard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(DashboardController.class)
class DashboardBackendApplicationTests{
 @Autowired MockMvc mvc;@MockitoBean ScoreProjection scores;@MockitoBean ProducerGateway producer;@MockitoBean com.live_jgv.dashboard.domain.port.in.GetMatchSnapshot snapshots;
 @Test void returnsRealProjectionThroughApi()throws Exception{when(scores.view("m")).thenReturn(Map.of("match_id","m","home_score",2));mvc.perform(get("/api/matches/m")).andExpect(status().isOk()).andExpect(jsonPath("$.home_score").value(2));}
 @Test void rejectsGoalForForeignTeamBeforePublishing()throws Exception{when(scores.view("m")).thenReturn(Map.of("home_team_id","h","away_team_id","a"));mvc.perform(post("/api/matches/m/events").contentType("application/json").content("{\"event_type\":\"GOAL\",\"team_id\":\"other\"}")).andExpect(status().isBadRequest());verifyNoInteractions(producer);}
 @Test void refusesUnknownMatch()throws Exception{when(scores.view("absent")).thenThrow(new NoSuchElementException("Partido no encontrado"));mvc.perform(get("/api/matches/absent")).andExpect(status().isNotFound());}
}
