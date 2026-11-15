package com.live_jgv.matchstate.domain.port;

import com.live_jgv.matchstate.domain.model.InputRecord;

@FunctionalInterface
public interface IncomingEventHandler {
    void handle(InputRecord record);
}
