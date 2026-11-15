package com.live_jgv.live_feed_producer.entrypoint.rest;

import com.live_jgv.live_feed_producer.domain.model.MatchEvent;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "live-feed.integration-rest.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MatchSimulationControllerTest {
    @Autowired MockMvc mvc;
    @MockBean MatchEventPublisher publisher;
    private static final String GOAL = """
            {"eventId":"g1","matchId":"match-001","eventType":"GOAL",
             "teamId":"team-home","minute":10,"timestamp":"2026-11-14T20:00:00.123456789Z",
             "referenceEventId":null}
            """;

    @BeforeEach
    void acknowledge() {
        when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private MvcResult submit(String body) throws Exception {
        return mvc.perform(post("/api/integration/events").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOAL", "VAR_CHECK", "GOAL_ANNULLED", "MATCH_CONTINUES"})
    void publishesEachEventWithoutChangingIdentity(String type) throws Exception {
        var body = GOAL.replace("\"GOAL\"", "\"" + type + "\"")
                .replace("\"referenceEventId\":null", "\"referenceEventId\":\"original-goal\"");
        var result = submit(body);
        assertTrue(result.getRequest().isAsyncStarted());
        mvc.perform(asyncDispatch(result)).andExpect(status().isNoContent());
        var event = org.mockito.ArgumentCaptor.forClass(MatchEvent.class);
        verify(publisher).publish(event.capture());
        assertEquals("g1", event.getValue().eventId());
        assertEquals("match-001", event.getValue().matchId());
        assertEquals(type, event.getValue().eventType().name());
        assertEquals("original-goal", event.getValue().referenceEventId());
        assertEquals(Instant.parse("2026-11-14T20:00:00.123456789Z"), event.getValue().timestamp());
    }

    @Test
    void repeatsExactlyTheSameEventForDuplicateRequests() throws Exception {
        mvc.perform(asyncDispatch(submit(GOAL))).andExpect(status().isNoContent());
        mvc.perform(asyncDispatch(submit(GOAL))).andExpect(status().isNoContent());
        var events = org.mockito.ArgumentCaptor.forClass(MatchEvent.class);
        verify(publisher, times(2)).publish(events.capture());
        assertEquals(events.getAllValues().get(0), events.getAllValues().get(1));
    }

    @Test
    void doesNotCompleteHttpRequestBeforeKafkaAcknowledges() throws Exception {
        var pending = new CompletableFuture<Void>();
        when(publisher.publish(any())).thenReturn(pending);
        var result = submit(GOAL);
        assertTrue(result.getRequest().isAsyncStarted());
        assertFalse(result.getRequest().getAsyncContext().getResponse().isCommitted());
        assertThrows(IllegalStateException.class, () -> result.getAsyncResult(20));
        pending.complete(null);
        mvc.perform(asyncDispatch(result)).andExpect(status().isNoContent());
    }

    @Test
    void asynchronousPublicationFailureReturns503WithoutDetails() throws Exception {
        when(publisher.publish(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("private broker detail")));
        mvc.perform(asyncDispatch(submit(GOAL))).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));
    }

    @Test
    void synchronousPublicationFailureReturns503() throws Exception {
        when(publisher.publish(any())).thenThrow(new IllegalStateException("private broker detail"));
        mvc.perform(asyncDispatch(submit(GOAL))).andExpect(status().isServiceUnavailable());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{", 
            "{\"eventId\":\"g1\",\"matchId\":\"m1\",\"eventType\":\"GOAL\",\"minute\":10,\"timestamp\":\"2026-11-14T20:00:00Z\"}",
            "{\"eventId\":\"a1\",\"matchId\":\"m1\",\"eventType\":\"GOAL_ANNULLED\",\"minute\":10,\"timestamp\":\"2026-11-14T20:00:00Z\"}"
    })
    void rejectsInvalidBodies(String body) throws Exception {
        mvc.perform(post("/api/integration/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "matchId", "eventType", "minute", "timestamp"})
    void rejectsMissingRequiredFields(String field) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(GOAL);
        json.remove(field);
        mvc.perform(post("/api/integration/events").contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"blankId", "badType", "badTimestamp", "numericTimestamp", "negativeMinute", "largeMinute", "fractionMinute", "blankReference"})
    void rejectsInvalidValues(String kind) throws Exception {
        var body = switch (kind) {
            case "blankId" -> GOAL.replace("\"g1\"", "\" \"");
            case "badType" -> GOAL.replace("\"GOAL\"", "\"UNKNOWN\"");
            case "badTimestamp" -> GOAL.replace("2026-11-14T20:00:00.123456789Z", "yesterday");
            case "numericTimestamp" -> GOAL.replace("\"2026-11-14T20:00:00.123456789Z\"", "123");
            case "negativeMinute" -> GOAL.replace("\"minute\":10", "\"minute\":-1");
            case "largeMinute" -> GOAL.replace("\"minute\":10", "\"minute\":131");
            case "fractionMinute" -> GOAL.replace("\"minute\":10", "\"minute\":10.5");
            default -> GOAL.replace("\"referenceEventId\":null", "\"referenceEventId\":\" \"");
        };
        mvc.perform(post("/api/integration/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }
}
