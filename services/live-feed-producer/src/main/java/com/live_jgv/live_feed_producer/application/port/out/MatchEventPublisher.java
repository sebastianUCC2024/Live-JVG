package com.live_jgv.live_feed_producer.application.port.out;

import com.live_jgv.live_feed_producer.domain.model.MatchEvent;
import java.util.concurrent.CompletableFuture;

/** Puerto de salida para publicar eventos deportivos validados. */
@FunctionalInterface
public interface MatchEventPublisher {
    /** Finaliza tras la confirmación del transporte; propaga los fallos. */
    CompletableFuture<Void> publish(MatchEvent event);
}
