package com.live_jgv.matchstate.application.port.in;

import com.live_jgv.matchstate.domain.model.InputRecord;

/** Accepts a raw source record for durable classification before transport acknowledgment. */
@FunctionalInterface
public interface IncomingEventHandler {
    void handle(InputRecord record);
}
