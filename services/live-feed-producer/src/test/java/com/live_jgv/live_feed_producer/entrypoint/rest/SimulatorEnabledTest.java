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
import java.util.concurrent.TimeoutException;
import com.live_jgv.live_feed_producer.domain.model.MatchEvent;
import com.live_jgv.live_feed_producer.application.service.EventPreparationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=MatchSimulationController.class, properties={"simulator.write-enabled=true","MATCH_ID=m"})
@Import(MatchSimulationService.class)
class SimulatorEnabledTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean MatchEventPublisher publisher;
    private static final String JSON="""
        {"event_id":"stable-id","event_type":"GOAL","team_id":"home","minute":10,
         "timestamp":"2026-11-15T00:00:00.123456789Z","reference_event_id":null}
        """;

    @Test void generatedIdentitySurvivesAsyncFailureAndExactRetry() throws Exception {
        var broker = new CompletableFuture<Void>();
        when(publisher.publish(any())).thenReturn(broker, CompletableFuture.completedFuture(null));
        var generated = mapper.readTree(JSON).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) generated).remove(java.util.List.of("event_id", "timestamp"));
        var first = mvc.perform(post("/api/matches/m/events").contentType("application/json")
                .content(mapper.writeValueAsBytes(generated))).andExpect(request().asyncStarted()).andReturn();
        broker.completeExceptionally(new IllegalStateException("private broker configuration"));
        var failed = mvc.perform(asyncDispatch(first)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PUBLICATION_UNCONFIRMED"))
                .andExpect(jsonPath("$.event.event_id").isNotEmpty())
                .andExpect(jsonPath("$.event.timestamp").isNotEmpty()).andReturn();
        var body = mapper.readTree(failed.getResponse().getContentAsString());
        assertFalse(body.toString().contains("private broker configuration"));
        var retry = (com.fasterxml.jackson.databind.node.ObjectNode) body.get("event").deepCopy();
        retry.remove("match_id");
        var second = mvc.perform(post("/api/matches/m/events").contentType("application/json")
                .content(mapper.writeValueAsBytes(retry))).andReturn();
        var success = mvc.perform(asyncDispatch(second)).andExpect(status().isAccepted()).andReturn();
        assertEquals(body.get("event"), mapper.readTree(success.getResponse().getContentAsString()));
        var events = ArgumentCaptor.forClass(MatchEvent.class);
        verify(publisher, times(2)).publish(events.capture());
        assertEquals(events.getAllValues().get(0), events.getAllValues().get(1));
    }

    @Test void timeoutPreservesClientIdentityAndDoesNotClaimRejection() throws Exception {
        when(publisher.publish(any())).thenReturn(CompletableFuture.failedFuture(new TimeoutException("secret endpoint")));
        var request = mvc.perform(post("/api/matches/m/events").contentType("application/json").content(JSON)).andReturn();
        var response = mvc.perform(asyncDispatch(request)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PUBLICATION_UNCONFIRMED"))
                .andExpect(jsonPath("$.event.event_id").value("stable-id"))
                .andExpect(jsonPath("$.event.timestamp").value("2026-11-15T00:00:00.123456789Z")).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("secret endpoint"));
    }

    @Test void preparationFailureIs500WithRecoverableEventAndSafeMessage() throws Exception {
        when(publisher.publish(any())).thenReturn(CompletableFuture.failedFuture(
                new EventPreparationException(new IllegalStateException("private serializer details"))));
        var request = mvc.perform(post("/api/matches/m/events").contentType("application/json").content(JSON)).andReturn();
        var response = mvc.perform(asyncDispatch(request)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("EVENT_PREPARATION_FAILED"))
                .andExpect(jsonPath("$.event.event_id").value("stable-id")).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("private serializer details"));
    }

    @Test void immediatePublisherFailureAlsoPreservesIdentity() throws Exception {
        when(publisher.publish(any())).thenThrow(new IllegalStateException("private transport details"));
        var request = mvc.perform(post("/api/matches/m/events").contentType("application/json").content(JSON)).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PUBLICATION_UNCONFIRMED"))
                .andExpect(jsonPath("$.event.event_id").value("stable-id"));
    }

    @Test void partialClientIdentityIs400AndNeverPublished() throws Exception {
        var partial = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(JSON);
        for (var field : java.util.List.of("timestamp", "event_id")) {
            var request = partial.deepCopy(); request.remove(field);
            mvc.perform(post("/api/matches/m/events").contentType("application/json")
                    .content(mapper.writeValueAsBytes(request))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(publisher);
    }

    @Test void successfulServerGeneratedIdentityKeepsOriginalResponseShape() throws Exception {
        when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
        var generated = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(JSON);
        generated.remove(java.util.List.of("event_id", "timestamp"));
        var request = mvc.perform(post("/api/matches/m/events").contentType("application/json")
                .content(mapper.writeValueAsBytes(generated))).andReturn();
        var response = mvc.perform(asyncDispatch(request)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.event_id").isNotEmpty()).andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.event").doesNotExist()).andReturn();
        var event = ArgumentCaptor.forClass(MatchEvent.class); verify(publisher).publish(event.capture());
        assertEquals(mapper.valueToTree(event.getValue()), mapper.readTree(response.getResponse().getContentAsString()));
    }

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
