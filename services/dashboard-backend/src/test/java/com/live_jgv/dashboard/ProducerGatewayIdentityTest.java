package com.live_jgv.dashboard;

import java.util.Map;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ProducerGatewayIdentityTest {
    @Test void failedResponseAndRetryForwardExactlyTheSameBody() {
        var builder=RestClient.builder().baseUrl("http://producer.invalid");
        var server=MockRestServiceServer.bindTo(builder).build();
        var gateway=new ProducerGateway("http://producer.invalid");
        ReflectionTestUtils.setField(gateway,"client",builder.build());
        var body=Map.<String,Object>of("event_id","id","timestamp","2026-11-15T00:00:00Z","event_type","GOAL");
        String json="{\"event_id\":\"id\",\"timestamp\":\"2026-11-15T00:00:00Z\",\"event_type\":\"GOAL\"}";
        server.expect(requestTo("http://producer.invalid/api/matches/m/events")).andExpect(content().json(json))
                .andRespond(request->{throw new IOException("response lost");});
        server.expect(requestTo("http://producer.invalid/api/matches/m/events")).andExpect(content().json(json))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(json));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,()->gateway.publish("m",body)).getStatusCode());
        assertEquals(body,gateway.publish("m",body));server.verify();
    }
}
