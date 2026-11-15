package com.live_jgv.dashboard.infrastructure.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.dashboard.domain.model.*;
import com.live_jgv.dashboard.domain.port.in.ProcessScoreUpdate;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.dynamic=false",
        "management.health.rabbit.enabled=false"
})
class WebSocketRecoveryIntegrationTest {
    @LocalServerPort int port;
    @Autowired ProcessScoreUpdate processor;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired SimpleBrokerMessageHandler broker;
    @Autowired WebSocketSessions sessions;

    private ScoreUpdate update(String match, String event, int home, EventType type) {
        return new ScoreUpdate(match, home, 0, event, type, Instant.parse("2026-11-15T00:12:12.079218900Z"),
                type == EventType.VAR_CHECK ? VarStatus.IN_PROGRESS : VarStatus.CLEAR,
                type == EventType.GOAL_ANNULLED ? "goal" : null);
    }

    @Test
    void realHandshakeSubscriptionIsolationDisconnectReconnectAndRecovery() throws Exception {
        String match = "recovery-match";
        try (var client = connect("http://localhost:5173"); var other = connect("http://localhost:5173")) {
            client.subscribe(match);
            other.subscribe("other-match");
            processor.process(update(match, "goal", 1, EventType.GOAL));
            var first = mapper.readTree(client.nextMessage());
            assertEquals(match, first.path("match_id").asText());
            assertEquals(1, first.path("home_score").asInt());
            assertEquals("GOAL", first.path("event_type").asText());
            assertEquals(Instant.parse("2026-11-15T00:12:12.079218900Z"), Instant.parse(first.path("updated_at").asText()));
            assertNull(other.frames.poll(200, TimeUnit.MILLISECONDS));
            client.close();
            processor.process(update(match, "var", 1, EventType.VAR_CHECK));
            processor.process(update(match, "annulled", 0, EventType.GOAL_ANNULLED));
            try (var reconnected = connect("http://localhost:5173")) {
                reconnected.subscribe(match);
                var response = rest.getForEntity("/api/matches/" + match + "/score", JsonNode.class);
                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertEquals("no-store", response.getHeaders().getCacheControl());
                var snapshot = response.getBody();
                assertNotNull(snapshot);
                assertEquals(0, snapshot.path("home_score").asInt());
                assertEquals("GOAL_ANNULLED", snapshot.path("event_type").asText());
                assertEquals("goal", snapshot.path("reference_event_id").asText());
                assertEquals(first.path("stream_id"), snapshot.path("stream_id"));
                assertTrue(snapshot.path("revision").asLong() > first.path("revision").asLong());
                reconnected.socket.sendText("SUBSCRIBE\nid:recovery\ndestination:/app/matches/"
                        + match + "/snapshot\n\n\0", true).join();
                var recovered = mapper.readTree(reconnected.nextMessage());
                assertTrue(recovered.path("available").asBoolean());
                assertEquals(snapshot, recovered.path("snapshot"));
                processor.process(update(match, "continue", 0, EventType.MATCH_CONTINUES));
                var live = mapper.readTree(reconnected.nextMessage());
                assertEquals(snapshot.path("stream_id"), live.path("stream_id"));
                assertTrue(live.path("revision").asLong() > snapshot.path("revision").asLong());
            }
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> sessions.activeCount() == 0);
    }

    @Test
    void unknownSnapshotReturns404AndCorsUsesConfiguredOrigins() {
        var headers = new HttpHeaders();
        headers.setOrigin("http://localhost:5173");
        var response = rest.exchange("/api/matches/unknown/score", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("http://localhost:5173", response.getHeaders().getAccessControlAllowOrigin());
        headers.setOrigin("https://untrusted.example");
        var denied = rest.exchange("/api/matches/unknown/score", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
    }

    @Test
    void websocketRejectsUnconfiguredOrigin() {
        assertThrows(ExecutionException.class, () -> connect("https://untrusted.example"));
    }

    private Browser connect(String origin) throws Exception {
        var client = new Browser();
        client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", origin).subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), client).get(5, TimeUnit.SECONDS);
        client.socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0", true).join();
        assertTrue(client.nextMessage().startsWith("CONNECTED"));
        return client;
    }

    private class Browser implements java.net.http.WebSocket.Listener, AutoCloseable {
        java.net.http.WebSocket socket;
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean closed;

        @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(java.net.http.WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            int end;
            while ((end = partial.indexOf("\0")) >= 0) {
                frames.add(partial.substring(0, end).stripLeading());
                partial.delete(0, end + 1);
            }
            socket.request(1);
            return null;
        }
        @Override public void onError(java.net.http.WebSocket socket, Throwable error) { failure.set(error); }

        String nextMessage() throws Exception {
            var frame = frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "No STOMP frame received; transport error=" + failure.get());
            return frame.startsWith("MESSAGE") ? frame.substring(frame.indexOf("\n\n") + 2) : frame;
        }

        void subscribe(String match) {
            var destination = WebSocketScorePublisher.destination(match);
            socket.sendText("SUBSCRIBE\nid:score\ndestination:" + destination + "\nack:auto\n\n\0", true).join();
            var lookup = MessageBuilder.withPayload(new byte[0])
                    .setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.MESSAGE)
                    .setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, destination).build();
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    !broker.getSubscriptionRegistry().findSubscriptions(lookup).isEmpty());
        }

        @Override public void close() {
            if (!closed && socket != null) {
                closed = true;
                socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join();
            }
        }
    }
}
