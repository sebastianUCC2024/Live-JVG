package com.live_jgv.dashboard.infrastructure.websocket;

import com.live_jgv.dashboard.infrastructure.web.DashboardWebProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.*;
import static org.junit.jupiter.api.Assertions.*;

class StompLifecycleTest {
    @Test
    void acceptsSingleMatchSubscriptionAndRejectsInjectionAndWildcards() {
        var interceptor = new StompInboundInterceptor();
        var headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        headers.setDestination("/topic/matches/m1/score");
        var valid = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertSame(valid, interceptor.preSend(valid, null));
        for (var destination : List.of("/topic/matches/*/score", "/topic/matches/m1/other", "/topic/other")) {
            var invalid = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            invalid.setDestination(destination);
            assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(
                    MessageBuilder.createMessage(new byte[0], invalid.getMessageHeaders()), null));
        }
        var send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setDestination("/topic/matches/m1/score");
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], send.getMessageHeaders()), null));
    }

    @Test
    void duplicateDisconnectIsSafeAndReconnectUsesNewSession() {
        var tracker = new WebSocketSessions();
        var headers = StompHeaderAccessor.create(StompCommand.CONNECTED);
        headers.setSessionId("session1");
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        tracker.connected(new SessionConnectedEvent(this, message));
        tracker.connected(new SessionConnectedEvent(this, message));
        assertEquals(1, tracker.activeCount());
        var disconnect = new SessionDisconnectEvent(this, message, "session1", CloseStatus.NORMAL);
        tracker.disconnected(disconnect);
        tracker.disconnected(disconnect);
        assertEquals(0, tracker.activeCount());
        headers = StompHeaderAccessor.create(StompCommand.CONNECTED);
        headers.setSessionId("session2");
        tracker.connected(new SessionConnectedEvent(this,
                MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders())));
        assertEquals(1, tracker.activeCount());
    }

    @Test
    void requiresExplicitOrigins() {
        assertDoesNotThrow(() -> new DashboardWebProperties(List.of("https://sports.example", "http://localhost:5173")));
        assertThrows(IllegalArgumentException.class, () -> new DashboardWebProperties(List.of()));
        for (var origin : List.of("*", "", "https://*.example", "https://sports.example/path", "ftp://sports.example")) {
            assertThrows(IllegalArgumentException.class, () -> new DashboardWebProperties(List.of(origin)));
        }
    }
}
