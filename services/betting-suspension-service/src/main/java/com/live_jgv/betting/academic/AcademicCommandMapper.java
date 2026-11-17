package com.live_jgv.betting.academic;

import com.live_jgv.betting.domain.SportEvent;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class AcademicCommandMapper {
    public AcademicCommand map(SportEvent event) {
        Objects.requireNonNull(event);
        var action=switch(event.eventType()) {
            case GOAL, VAR_CHECK -> AcademicCommand.CommandType.SUSPEND_BETS;
            case GOAL_ANNULLED, MATCH_CONTINUES -> AcademicCommand.CommandType.RESUME_BETS;
        };
        String identity="academic-v1:"+event.matchId().length()+":"+event.matchId()+":"
                +event.eventId().length()+":"+event.eventId();
        String id=UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        return new AcademicCommand(1,id,event.matchId(),event.eventId(),action,event.timestamp());
    }
}
