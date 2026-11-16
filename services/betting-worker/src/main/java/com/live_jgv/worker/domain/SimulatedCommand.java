package com.live_jgv.worker.domain;

import java.time.Instant;
import java.util.Objects;

/** Provisional local command: deliberately contains no financial fields. */
public record SimulatedCommand(String commandId,String operationId,String matchId,String simulatedUserId,Instant timestamp) {
    public SimulatedCommand {
        for(String id:new String[]{commandId,operationId,matchId,simulatedUserId})
            if(id==null||id.isBlank()) throw new IllegalArgumentException("Command identity required");
        Objects.requireNonNull(timestamp);
    }
}
