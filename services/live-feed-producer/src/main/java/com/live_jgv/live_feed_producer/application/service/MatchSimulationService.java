package com.live_jgv.live_feed_producer.application.service;

import com.live_jgv.live_feed_producer.domain.model.*;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Service
public class MatchSimulationService {
    private final MatchEventPublisher publisher;
    private final String matchId, home, away;
    public MatchSimulationService(MatchEventPublisher publisher, @Value("${MATCH_ID:mvp-match-001}") String matchId,
                                  @Value("${HOME_TEAM_ID:home}") String home, @Value("${AWAY_TEAM_ID:away}") String away) {
        this.publisher=publisher;this.matchId=matchId;this.home=home;this.away=away;
    }
    public CompletableFuture<MatchEvent> publish(String match, String id, EventType type, String team, int minute, Instant timestamp, String reference) {
        OutgoingReferencePolicy.validate(reference);
        if (!matchId.equals(match)) throw new IllegalArgumentException("Partido no registrado en el simulador");
        if(team!=null && !team.equals(home) && !team.equals(away)) throw new IllegalArgumentException("Equipo ajeno al partido");
        if((id==null)!=(timestamp==null)) throw new IllegalArgumentException("event_id y timestamp deben enviarse juntos");
        var event=id==null?MatchEvent.create(match,type,team,minute,Instant.now(),reference):new MatchEvent(id,match,type,team,minute,timestamp,reference);
        return publisher.publish(event).thenApply(ignored->event);
    }
}
