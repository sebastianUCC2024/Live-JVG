package com.live_jgv.notification.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveAlertV1Test {
    @Test void goalRetainsTheProducerIdentity() {
        var id=UUID.randomUUID();
        var alert=new LiveAlertV1("1.0",id,"match","goal",ScoreUpdate.EventType.GOAL,
                "Gol registrado",Instant.EPOCH,null,new ScoreUpdate("match","goal",ScoreUpdate.EventType.GOAL,1,0,ScoreUpdate.VarStatus.CLEAR,Instant.EPOCH,null));
        assertEquals(id,alert.alertId());
        assertEquals("goal",alert.sourceEventId());
    }
    @Test void annulmentRetainsItsOriginalGoalReference() {
        var alert=new LiveAlertV1("1.0",UUID.randomUUID(),"match","decision",
                ScoreUpdate.EventType.GOAL_ANNULLED,"Gol anulado",Instant.EPOCH,"goal",new ScoreUpdate("match","decision",ScoreUpdate.EventType.GOAL_ANNULLED,0,0,ScoreUpdate.VarStatus.CLEAR,Instant.EPOCH,"goal"));
        assertEquals("goal",alert.referenceEventId());
        assertThrows(IllegalArgumentException.class,()->new LiveAlertV1("1.0",alert.alertId(),"match",
                "decision",ScoreUpdate.EventType.GOAL_ANNULLED,"Gol anulado",Instant.EPOCH,null,alert.snapshot()));
    }
}
