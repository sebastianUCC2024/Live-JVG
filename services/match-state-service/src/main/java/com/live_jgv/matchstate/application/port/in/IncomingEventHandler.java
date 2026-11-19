package com.live_jgv.matchstate.application.port.in;

import com.live_jgv.matchstate.domain.model.InputRecord;

/** Recibe el registro original para clasificarlo antes de confirmar el transporte. */
@FunctionalInterface
public interface IncomingEventHandler {
    void handle(InputRecord record);
}
