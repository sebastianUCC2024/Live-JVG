package com.live_jgv.dashboard.infrastructure.websocket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.*;

@Component
public final class WebSocketSessions {
    private static final Logger log = LoggerFactory.getLogger(WebSocketSessions.class);
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    @EventListener
    public void connected(SessionConnectedEvent event) {
        var id = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (id != null && sessions.add(id)) {
            log.info("Dashboard STOMP connection established; activeSessions={}", sessions.size());
        }
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        if (sessions.remove(event.getSessionId())) {
            log.info("Dashboard STOMP connection closed; activeSessions={}", sessions.size());
        }
    }

    public int activeCount() {
        return sessions.size();
    }
}
