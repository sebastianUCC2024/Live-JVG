package com.live_jgv.dashboard.infrastructure.websocket;

import com.live_jgv.dashboard.domain.model.MatchSnapshot;
import com.live_jgv.dashboard.domain.port.out.MatchSnapshotPublisher;
import com.live_jgv.dashboard.infrastructure.web.MatchSnapshotResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

@Component
public final class WebSocketScorePublisher implements MatchSnapshotPublisher {
    private final SimpMessagingTemplate template;

    public WebSocketScorePublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void publish(MatchSnapshot snapshot) {
        template.convertAndSend(destination(snapshot.update().matchId()), MatchSnapshotResponse.from(snapshot));
    }

    public static String destination(String matchId) {
        return "/topic/matches/" + UriUtils.encodePathSegment(matchId, StandardCharsets.UTF_8) + "/score";
    }
}
