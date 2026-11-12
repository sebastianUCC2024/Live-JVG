package com.live_jgv.matchstate.domain.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable aggregate. Only accepted events contribute to state and history. */
public final class MatchState {
    private final String matchId;
    private final String homeTeamId;
    private final String awayTeamId;
    private final Map<String, MatchEvent> processedEvents;
    private final Set<String> annulledGoalIds;
    private final VarStatus varStatus;

    private MatchState(String matchId, String homeTeamId, String awayTeamId,
                       Map<String, MatchEvent> events, Set<String> annulled, VarStatus status) {
        this.matchId = matchId;
        this.homeTeamId = homeTeamId;
        this.awayTeamId = awayTeamId;
        this.processedEvents = Map.copyOf(events);
        this.annulledGoalIds = Set.copyOf(annulled);
        this.varStatus = status;
    }

    public static MatchState start(String matchId, String homeTeamId, String awayTeamId) {
        requireIdentifier(matchId);
        requireIdentifier(homeTeamId);
        requireIdentifier(awayTeamId);
        if (homeTeamId.equals(awayTeamId)) {
            throw new IllegalArgumentException("Home and away teams must differ");
        }
        return new MatchState(matchId, homeTeamId, awayTeamId, Map.of(), Set.of(), VarStatus.CLEAR);
    }

    public MatchState apply(MatchEvent event) {
        if (event == null) {
            throw new InvalidMatchEventException("Event must not be null");
        }
        if (!matchId.equals(event.matchId())) {
            throw new InvalidMatchEventException("Event belongs to another match");
        }
        var previous = processedEvents.get(event.eventId());
        if (previous != null) {
            if (!previous.equals(event)) {
                throw new InvalidMatchEventException("Event ID already used by a different payload");
            }
            return this;
        }
        if (event.teamId() != null && !homeTeamId.equals(event.teamId()) && !awayTeamId.equals(event.teamId())) {
            throw new InvalidMatchEventException("Unknown team for this match");
        }
        var annulled = new HashSet<>(annulledGoalIds);
        var status = varStatus;
        switch (event.eventType()) {
            case GOAL -> { /* Score is derived from the accepted goal ledger. */ }
            case VAR_CHECK -> {
                if (event.referenceEventId() != null) {
                    activeGoal(event.referenceEventId());
                }
                status = VarStatus.IN_PROGRESS;
            }
            case GOAL_ANNULLED -> {
                var goal = activeGoal(event.referenceEventId());
                if (event.teamId() != null && !event.teamId().equals(goal.teamId())) {
                    throw new InvalidMatchEventException("Annulment team differs from original goal team");
                }
                annulled.add(goal.eventId());
                status = VarStatus.CLEAR;
            }
            case MATCH_CONTINUES -> status = VarStatus.CLEAR;
        }
        var events = new HashMap<>(processedEvents);
        events.put(event.eventId(), event);
        return new MatchState(matchId, homeTeamId, awayTeamId, events, annulled, status);
    }

    private MatchEvent activeGoal(String reference) {
        var goal = processedEvents.get(reference);
        if (goal == null || goal.eventType() != EventType.GOAL) {
            throw new InvalidMatchEventException("Reference must identify a previously accepted goal in this match");
        }
        if (annulledGoalIds.contains(reference)) {
            throw new InvalidMatchEventException("Goal has already been annulled");
        }
        return goal;
    }

    public Score score() {
        int home = 0;
        int away = 0;
        for (var event : processedEvents.values()) {
            if (event.eventType() == EventType.GOAL && !annulledGoalIds.contains(event.eventId())) {
                if (homeTeamId.equals(event.teamId())) { home++; } else { away++; }
            }
        }
        return new Score(home, away);
    }

    public Map<String, MatchEvent> goals() {
        return processedEvents.values().stream().filter(event -> event.eventType() == EventType.GOAL)
                .collect(Collectors.toUnmodifiableMap(MatchEvent::eventId, event -> event));
    }

    public String matchId() { return matchId; }
    public String homeTeamId() { return homeTeamId; }
    public String awayTeamId() { return awayTeamId; }
    public Map<String, MatchEvent> processedEvents() { return processedEvents; }
    public Set<String> annulledGoalIds() { return annulledGoalIds; }
    public VarStatus varStatus() { return varStatus; }

    private static void requireIdentifier(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Match and team identifiers must not be blank");
        }
    }
}
