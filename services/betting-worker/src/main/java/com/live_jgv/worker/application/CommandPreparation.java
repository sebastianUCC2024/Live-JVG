package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.DecodedCommand;
import com.live_jgv.worker.domain.SimulatedCommand;
import com.live_jgv.worker.domain.TransportMessage;

/** Puerto de salida para conservar la representación canónica del comando interno. */
@FunctionalInterface
public interface CommandPreparation {
    Input prepare(SimulatedCommand command);

    /** Los bytes y la identidad decodificada deben representar el mismo comando. */
    record Input(TransportMessage message, DecodedCommand decoded) { }
}
