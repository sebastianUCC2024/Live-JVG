package com.live_jgv.dashboard.infrastructure.websocket;

import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/** Permite suscripciones del navegador, pero impide inyectar marcadores. */
public final class StompInboundInterceptor implements ChannelInterceptor {
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers != null) {
            if (headers.getCommand() == StompCommand.SEND) {
                throw new MessageDeliveryException("Browser publication is not supported");
            }
            if (headers.getCommand() == StompCommand.SUBSCRIBE
                    && (headers.getDestination() == null || !headers.getDestination()
                            .matches("(?:/topic/matches/[^/*?{}]+/score|/app/matches/[^/*?{}]+/snapshot)"))) {
                throw new MessageDeliveryException("Subscribe to a single match score destination");
            }
        }
        return message;
    }
}
