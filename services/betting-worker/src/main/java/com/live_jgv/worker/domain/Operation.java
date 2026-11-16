package com.live_jgv.worker.domain;

import java.time.Instant;
import java.util.Objects;

public record Operation(String operationId,String commandId,String matchId,String simulatedUserId,
                        State state,String reason,long version,Instant timestamp,Instant receivedAt,Instant updatedAt) {
    public enum State { RECEIVED, ACCEPTED, REJECTED, BLOCKED, FAILED }
    public Operation {
        for(String id:new String[]{operationId,commandId,matchId,simulatedUserId})
            if(id==null||id.isBlank()) throw new IllegalArgumentException("Operation identity required");
        Objects.requireNonNull(state);Objects.requireNonNull(timestamp);Objects.requireNonNull(receivedAt);Objects.requireNonNull(updatedAt);
        if(version<0||(state==State.RECEIVED&&(version!=0||reason!=null))
                ||(state!=State.RECEIVED&&(version<1||reason==null||reason.isBlank())))
            throw new IllegalArgumentException("Invalid operation state");
    }
    public static Operation received(SimulatedCommand c,Instant now) {
        return new Operation(c.operationId(),c.commandId(),c.matchId(),c.simulatedUserId(),State.RECEIVED,null,0,c.timestamp(),now,now);
    }
    public Operation finish(State next,String reason,Instant now) {
        if(state!=State.RECEIVED||next==State.RECEIVED) throw new IllegalStateException("Invalid terminal transition");
        if(now.isBefore(receivedAt)) throw new IllegalArgumentException("Processing clock regressed");
        return new Operation(operationId,commandId,matchId,simulatedUserId,next,reason,Math.addExact(version,1),timestamp,receivedAt,now);
    }
}
