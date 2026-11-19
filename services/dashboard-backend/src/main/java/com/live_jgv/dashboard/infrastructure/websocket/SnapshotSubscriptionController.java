package com.live_jgv.dashboard.infrastructure.websocket;

import com.live_jgv.dashboard.application.port.in.GetMatchSnapshot;
import com.live_jgv.dashboard.infrastructure.web.MatchSnapshotResponse;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

@Controller
public class SnapshotSubscriptionController {
    private final GetMatchSnapshot query;

    public SnapshotSubscriptionController(GetMatchSnapshot query) {
        this.query = query;
    }

    /** Responde solo a la sesión solicitante, sin difundir la recuperación. */
    @SubscribeMapping("/matches/{matchId}/snapshot")
    public SnapshotReply snapshot(@DestinationVariable String matchId) {
        return query.find(matchId).map(value -> new SnapshotReply(true, MatchSnapshotResponse.from(value)))
                .orElseGet(() -> new SnapshotReply(false, null));
    }

    public record SnapshotReply(boolean available, MatchSnapshotResponse snapshot) { }
}
