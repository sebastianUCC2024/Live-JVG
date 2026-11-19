package com.live_jgv.matchstate.domain.model;
import java.time.Instant;
import java.util.*;
/** Valida la identidad y coherencia de la alerta con su snapshot de gol o VAR. */
public record LiveAlertV1(String contractVersion,UUID alertId,String matchId,String sourceEventId,
        EventType eventType,String message,Instant timestamp,String referenceEventId,ScoreUpdate snapshot) {
    public LiveAlertV1 {
        if(!"1.0".equals(contractVersion) || alertId==null || snapshot==null || timestamp==null || message==null || message.isBlank() || message.length()>500
            || !Objects.equals(matchId,snapshot.matchId()) || !Objects.equals(sourceEventId,snapshot.eventId()) || eventType!=snapshot.eventType()
            || !timestamp.equals(snapshot.updatedAt()) || !Objects.equals(referenceEventId,snapshot.referenceEventId())
            || (eventType==EventType.MATCH_CONTINUES || eventType==EventType.MATCH_FINISHED) || (eventType==EventType.GOAL_ANNULLED && referenceEventId==null))
            throw new IllegalArgumentException("Invalid LiveAlert V1 or incoherent snapshot");
    }
    public static Optional<LiveAlertV1> create(ScoreUpdate score) {
        if(score.eventType()==EventType.MATCH_CONTINUES || score.eventType()==EventType.MATCH_FINISHED) return Optional.empty();
        String prefix=switch(score.eventType()) { case GOAL -> "Gol registrado. "; case GOAL_ANNULLED -> "Gol anulado. "; case VAR_CHECK -> "Revisi\u00f3n VAR. "; default -> throw new IllegalArgumentException(); };
        String status=score.varStatus()==VarStatus.IN_PROGRESS?"Revisi\u00f3n VAR en curso. ":"";
        return Optional.of(new LiveAlertV1("1.0",UUID.randomUUID(),score.matchId(),score.eventId(),score.eventType(),prefix+status+"Marcador: "+score.homeScore()+"\u2013"+score.awayScore()+".",score.updatedAt(),score.referenceEventId(),score));
    }
}
