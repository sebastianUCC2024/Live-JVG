package com.live_jgv.matchstate.infrastructure.kafka;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import com.live_jgv.matchstate.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class VarFieldCompatibilityTest {
    private final com.fasterxml.jackson.databind.ObjectMapper mapper=new MessagingConfiguration().messagingObjectMapper();
    private static final String PREFIX="{\"event_id\":\"annul\",\"match_id\":\"m\",\"event_type\":\"GOAL_ANNULLED\",\"team_id\":\"home\",\"minute\":10,\"timestamp\":\"2026-11-15T00:00:00Z\",";
    MatchEvent read(String refs) throws Exception {return mapper.readValue(PREFIX+refs+"}",MatchEventMessage.class).toDomain();}
    @ParameterizedTest @ValueSource(strings={"\"annuls_event_id\":\"goal\"","\"reference_event_id\":\"goal\",\"annuls_event_id\":\"goal\"","\"annuls_event_id\":\"goal\",\"reference_event_id\":\"goal\"","\"reference_event_id\":null,\"annuls_event_id\":\"goal\""})
    void normalizedReplayNeverAnnulsTwice(String refs) throws Exception {
        var goal=new MatchEvent("goal","m",EventType.GOAL,"home",9,Instant.EPOCH,null);
        var initial=MatchState.start("m","home","away").apply(goal);var old=read("\"reference_event_id\":\"goal\"");var alias=read(refs);
        assertEquals(old,alias);var after=initial.apply(old);assertSame(after,after.apply(alias));
        assertEquals(mapper.writeValueAsString(old),mapper.writeValueAsString(alias));
        var changed=read("\"annuls_event_id\":\"other-goal\"");
        assertThrows(InvalidMatchEventException.class,()->after.apply(changed));
        var replay=MatchState.rebuilder("m","home","away");replay.accept(goal);replay.accept(alias);replay.accept(old);assertEquals(0,replay.finish().score().home());
    }
    @ParameterizedTest @ValueSource(strings={"\"annuls_event_id\":1","\"annuls_event_id\":true","\"annuls_event_id\":{}","\"annuls_event_id\":[]","\"annuls_event_id\":null","\"annuls_event_id\":\"\"","\"annuls_event_id\":\" \"","\"reference_event_id\":\"goal\",\"annuls_event_id\":\"other\"","\"annuls_event_id\":\"goal\",\"annuls_event_id\":\"other\"","\"reference_event_id\":7,\"annuls_event_id\":\"goal\""})
    void rejectsAmbiguityAndInvalidInput(String refs) {assertThrows(Exception.class,()->read(refs));}
    @Test void aliasCannotChangeContinuationSemantics() {assertThrows(Exception.class,()->mapper.readValue((PREFIX+"\"annuls_event_id\":\"goal\"}").replace("GOAL_ANNULLED","MATCH_CONTINUES"),MatchEventMessage.class));}
    @Test void unknownGoalAndSecondAnnulmentStillFailDomainRules() throws Exception {
        var state=MatchState.start("m","home","away");var annul=read("\"annuls_event_id\":\"goal\"");assertThrows(InvalidMatchEventException.class,()->state.apply(annul));
        var after=state.apply(new MatchEvent("goal","m",EventType.GOAL,"home",9,Instant.EPOCH,null)).apply(annul);
        assertThrows(InvalidMatchEventException.class,()->after.apply(new MatchEvent("another","m",EventType.GOAL_ANNULLED,"home",10,annul.timestamp(),"goal")));
    }
}
