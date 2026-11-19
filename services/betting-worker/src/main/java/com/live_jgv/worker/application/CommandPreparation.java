package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.DecodedCommand;
import com.live_jgv.worker.domain.SimulatedCommand;
import com.live_jgv.worker.domain.TransportMessage;

/** Output boundary for preserving the canonical receipt of an internal domain command. */
@FunctionalInterface
public interface CommandPreparation {
    Input prepare(SimulatedCommand command);

    /** Canonical bytes and their decoded identity must represent the same command. */
    record Input(TransportMessage message, DecodedCommand decoded) { }
}
