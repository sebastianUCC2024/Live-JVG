package com.live_jgv.dashboard;
import com.live_jgv.dashboard.infrastructure.jdbc.ScoreProjection;
import com.live_jgv.dashboard.infrastructure.http.ProducerGateway;
import com.live_jgv.dashboard.infrastructure.web.DashboardController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.dashboard.infrastructure.web.ApiExceptionHandler;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC/controller/gateway, controlled HTTP transport; no producer or database. */
class ProducerGatewayRecoveryTest {
    private final ObjectMapper json=new ObjectMapper();
    private final MockRestServiceServer upstream;
    private final MockMvc mvc;
    ProducerGatewayRecoveryTest() {
        var builder=RestClient.builder().baseUrl("http://producer.invalid");
        upstream=MockRestServiceServer.bindTo(builder).build();
        var gateway=new ProducerGateway("http://producer.invalid");
        ReflectionTestUtils.setField(gateway,"client",builder.build());
        var scores=mock(ScoreProjection.class);
        when(scores.view("m")).thenReturn(Map.of("home_team_id","home","away_team_id","away"));
        mvc=MockMvcBuilders.standaloneSetup(new DashboardController(scores,gateway,true))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    private Map<String,Object> request() {
        var value=new LinkedHashMap<String,Object>();
        value.put("event_id","original-id");value.put("event_type","GOAL");value.put("team_id","home");
        value.put("minute",10);value.put("timestamp","2026-11-16T20:00:00.123456789Z");value.put("reference_event_id",null);
        return value;
    }
    private Map<String,Object> event() { var value=request();value.put("match_id","m");return value; }
    private String code(int status) { return status==500?"EVENT_PREPARATION_FAILED":"PUBLICATION_UNCONFIRMED"; }
    private String respond(int status,Object returned,Map<String,Object> sent) throws Exception {
        var envelope=new LinkedHashMap<String,Object>();
        envelope.put("code",code(status));envelope.put("error","private-upstream-exception");
        envelope.put("event",returned);envelope.put("stack_trace","private-upstream-exception");
        return exchange(status,json.writeValueAsString(envelope),sent);
    }
    private String exchange(int status,String body,Map<String,Object> sent) throws Exception {
        String original=json.writeValueAsString(sent);
        upstream.expect(requestTo("http://producer.invalid/api/matches/m/events"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json(original))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
        var response=mvc.perform(post("/api/matches/m/events").contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().is(status)).andReturn().getResponse();
        upstream.verify();assertEquals(original,json.writeValueAsString(sent));
        String result=response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(result.contains("private-upstream-exception"));assertFalse(result.contains("stack_trace"));
        return result;
    }
    @ParameterizedTest @ValueSource(ints={500,503})
    void controllerExposesOnlySafeRecoveryFieldsAndOriginalHttpStatus(int status) throws Exception {
        var returned=event();returned.put("internal_details","private-upstream-exception");
        var body=json.readTree(respond(status,returned,request()));
        assertEquals(code(status),body.path("code").asText());
        assertEquals(json.valueToTree(event()),body.get("event"));
        assertEquals(Set.of("code","error","event"),json.convertValue(body,Map.class).keySet());
        assertFalse(body.path("error").asText().contains("confirmó"));
    }
    @ParameterizedTest @ValueSource(strings={"event_id","timestamp","match_id","team_id","minute","event_type","reference_event_id"})
    void incompatibleEventIsNotReturnedOrAllowedToReplaceTheOriginal(String field) throws Exception {
        var returned=event();returned.put(field,field.equals("minute")?11:"different");
        var body=json.readTree(respond(503,returned,request()));
        assertFalse(body.has("event"));assertTrue(body.path("error").asText().contains("incompatible"));
        assertEquals("PUBLICATION_UNCONFIRMED",body.path("code").asText());
    }
    @ParameterizedTest @ValueSource(strings={"missing-minute","fractional-minute","invalid-time","array-event"})
    void malformedEventIsOmitted(String scenario) throws Exception {
        var returned=event();
        if(scenario.equals("missing-minute"))returned.remove("minute");
        if(scenario.equals("fractional-minute"))returned.put("minute",1.5);
        if(scenario.equals("invalid-time"))returned.put("timestamp","not-an-instant");
        var body=json.readTree(respond(500,scenario.equals("array-event")?List.of(returned):returned,request()));
        assertFalse(body.has("event"));assertEquals("EVENT_PREPARATION_FAILED",body.path("code").asText());
    }
    @Test void serverGeneratedIdentityCanBeRecoveredWhenClientDidNotSupplyOne() throws Exception {
        var sent=request();sent.remove("event_id");sent.remove("timestamp");
        var body=json.readTree(respond(503,event(),sent));
        assertEquals(json.valueToTree(event()),body.get("event"));
    }
    @Test void equivalentTimestampFormattingDoesNotChangeTheInstantIdentity() throws Exception {
        var sent=request();sent.put("timestamp","2026-11-16T20:00:00.123456789+00:00");
        assertTrue(json.readTree(respond(503,event(),sent)).has("event"));
    }
    @Test void missingEventNeverFabricatesAnIdentity() throws Exception {
        var body=json.readTree(respond(500,null,request()));
        assertFalse(body.has("event"));assertEquals("EVENT_PREPARATION_FAILED",body.path("code").asText());
    }
    @ParameterizedTest @ValueSource(ints={400,409,500,503})
    void unknownOrStatusMismatchedCodesRetainPreviousErrorSemantics(int status) throws Exception {
        String body=exchange(status,"{\"code\":\"UNKNOWN\",\"error\":\"private-upstream-exception\"}",request());
        assertFalse(json.readTree(body).has("code"));assertFalse(json.readTree(body).has("event"));
    }
    @ParameterizedTest @ValueSource(strings={"not-json","{\"code\":\"PUBLICATION_UNCONFIRMED\",\"code\":\"PUBLICATION_UNCONFIRMED\"}","{\"code\":\"PUBLICATION_UNCONFIRMED\"} {}"})
    void malformedErrorEnvelopesAreNotPropagated(String body) throws Exception {
        assertFalse(json.readTree(exchange(503,body,request())).has("code"));
    }
    @ParameterizedTest @ValueSource(ints={500,503})
    void recognizedCodeWithWrongHttpStatusIsNotTrusted(int status) throws Exception {
        String body=json.writeValueAsString(Map.of("code",code(status==500?503:500),"event",event()));
        assertFalse(json.readTree(exchange(status,body,request())).has("event"));
    }
    @Test void oversizedEnvelopeIsNotPropagated() throws Exception {
        String body=json.writeValueAsString(Map.of("code",code(503),"event",event(),"internal","x".repeat(65536)));
        assertFalse(json.readTree(exchange(503,body,request())).has("code"));
    }
}
