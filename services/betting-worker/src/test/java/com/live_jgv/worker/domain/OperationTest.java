package com.live_jgv.worker.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class OperationTest {
    private final Instant now=Instant.EPOCH;
    private Operation received(){return Operation.received(new SimulatedCommand("c","o","m","u",now),now);}
    @ParameterizedTest @EnumSource(value=Operation.State.class,names={"ACCEPTED","REJECTED","BLOCKED","FAILED"})
    void receivedCanReachEachExplicitTerminalState(Operation.State state){
        var result=received().finish(state,"reason",now);assertEquals(state,result.state());assertEquals(1,result.version());
    }
    @Test void terminalTransitionsAreImmutableAndCannotBeReprocessed(){
        var blocked=received().finish(Operation.State.BLOCKED,"suspended",now);
        assertThrows(IllegalStateException.class,()->blocked.finish(Operation.State.ACCEPTED,"retry",now));
        assertThrows(IllegalStateException.class,()->received().finish(Operation.State.RECEIVED,"again",now));
    }
    @Test void invalidStateAndClockRegressionAreRejected(){
        assertThrows(IllegalArgumentException.class,()->received().finish(Operation.State.BLOCKED," ",now));
        assertThrows(IllegalArgumentException.class,()->received().finish(Operation.State.ACCEPTED,"ok",now.minusSeconds(1)));
    }
    @Test void transportEvidenceDefensivelyCopiesBody(){
        byte[] body={1};var m=new TransportMessage("s",null,"x","k",body,"{}");body[0]=2;assertEquals(1,m.body()[0]);m.body()[0]=3;assertEquals(1,m.body()[0]);
    }
}
