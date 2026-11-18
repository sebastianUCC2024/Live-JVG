package com.live_jgv.betting.academic;

import com.live_jgv.betting.domain.SportEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcademicCommandMapperTest {
    private void maps(String id,SportEvent.EventType type,String reference,AcademicCommand.CommandType expected) {
        var event=new SportEvent(id,"m",type,type==SportEvent.EventType.GOAL?"home":null,10,Instant.EPOCH,reference);
        var result=new AcademicCommandMapper().map(event);
        assertNotNull(result);assertEquals(expected,result.commandType());assertEquals(id,result.sourceEventId());
        assertEquals("m",result.matchId());assertEquals(1,result.schemaVersion());assertEquals(Instant.EPOCH,result.timestamp());
    }
    @Test void M01_goalSuspends(){maps("g",SportEvent.EventType.GOAL,null,AcademicCommand.CommandType.SUSPEND_BETS);}
    @Test void M02_varCheckSuspends(){maps("v",SportEvent.EventType.VAR_CHECK,"g",AcademicCommand.CommandType.SUSPEND_BETS);}
    @Test void M03_annulmentResumesSimulation(){maps("a",SportEvent.EventType.GOAL_ANNULLED,"g",AcademicCommand.CommandType.RESUME_BETS);}
    @Test void M04_continuationResumesSimulation(){maps("c",SportEvent.EventType.MATCH_CONTINUES,null,AcademicCommand.CommandType.RESUME_BETS);}
}
