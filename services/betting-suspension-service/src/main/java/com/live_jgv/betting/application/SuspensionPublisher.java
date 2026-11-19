package com.live_jgv.betting.application;

/** Retorna tras confirmar el enrutamiento; una publicación incierta permanece pendiente. */
public interface SuspensionPublisher {
    void publish(String messageId,String payload);
}
