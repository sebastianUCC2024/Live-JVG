package com.live_jgv.notification;

import com.live_jgv.notification.domain.*;
import java.time.Instant;

public final class Fixtures {
    private Fixtures() { }
    public static final String JSON = """
        {"match_id":"match-001","home_score":1,"away_score":0,"event_id":"goal-001",
         "event_type":"GOAL","updated_at":"2026-11-15T15:30:00.123456789Z",
         "var_status":"CLEAR","reference_event_id":null}
        """;
    public static ScoreUpdate update(ScoreUpdate.EventType type) {
        return new ScoreUpdate("match-001","event-"+type,type,1,0,
                type==ScoreUpdate.EventType.VAR_CHECK ? ScoreUpdate.VarStatus.IN_PROGRESS : ScoreUpdate.VarStatus.CLEAR,
                Instant.parse("2026-11-15T15:30:00.123456789Z"),type==ScoreUpdate.EventType.GOAL_ANNULLED ? "goal-original" : null);
    }
    public static LiveAlert alert() { return new NotificationPolicy().create(update(ScoreUpdate.EventType.GOAL),Instant.EPOCH).orElseThrow(); }
}
