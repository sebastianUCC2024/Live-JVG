package com.live_jgv.matchstate.domain.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable match aggregate whose accepted event ledger is the source of the score.
 * Annulment excludes an existing active goal instead of decrementing a mutable counter.
 * Reapplying an identical event returns this instance; reusing its ID with another payload fails.
 * V1 VAR state is a single flag: a check opens it and continuation or annulment clears it.
 * Finishing requires minute 15 and a resolved review; accepted history remains replayable.
 * No persistence or messaging side effects occur while validating a transition.
 */
public final class MatchState {
    private final String matchId;
    private final String homeTeamId;
    private final String awayTeamId;
    private final Map<String, MatchEvent> processedEvents;
    private final Set<String> annulledGoalIds;
    private final VarStatus varStatus;
    private final boolean finished;

    private MatchState(String matchId, String homeTeamId, String awayTeamId,
                       Map<String, MatchEvent> events, Set<String> annulled, VarStatus status, boolean finished) {
        this(matchId, homeTeamId, awayTeamId, events, annulled, status, finished, true);
    }

    private MatchState(String matchId, String homeTeamId, String awayTeamId,
                       Map<String, MatchEvent> events, Set<String> annulled, VarStatus status, boolean finished, boolean copy) {
        this.matchId = matchId;
        this.homeTeamId = homeTeamId;
        this.awayTeamId = awayTeamId;
        this.processedEvents = copy ? Map.copyOf(events) : events;
        this.annulledGoalIds = copy ? Set.copyOf(annulled) : annulled;
        this.varStatus = status;
        this.finished = finished;
    }

    public static MatchState start(String matchId, String homeTeamId, String awayTeamId) {
        requireIdentifier(matchId);
        requireIdentifier(homeTeamId);
        requireIdentifier(awayTeamId);
        if (homeTeamId.equals(awayTeamId)) {
            throw new IllegalArgumentException("Home and away teams must differ");
        }
        return new MatchState(matchId, homeTeamId, awayTeamId, Map.of(), Set.of(), VarStatus.CLEAR, false);
    }

    public MatchState apply(MatchEvent event) {
        return apply(event, true);
    }

    private MatchState apply(MatchEvent event, boolean copy) {
        if (event == null) {
            throw new InvalidMatchEventException("Event must not be null");
        }
        if (!matchId.equals(event.matchId())) {
            throw new InvalidMatchEventException("Event belongs to another match");
        }
        var previous = processedEvents.get(event.eventId());
        if (previous != null) {
            if (!previous.equals(event)) {
                throw new InvalidMatchEventException(InvalidMatchEventException.Reason.ID_CONFLICT, "Event ID already used by a different payload");
            }
            return this;
        }
        if (finished) {
            throw new InvalidMatchEventException("Match has already finished");
        }
        if (event.teamId() != null && !homeTeamId.equals(event.teamId()) && !awayTeamId.equals(event.teamId())) {
            throw new InvalidMatchEventException(InvalidMatchEventException.Reason.UNKNOWN_TEAM, "Unknown team for this match");
        }
        var annulled = copy ? new HashSet<>(annulledGoalIds) : annulledGoalIds;
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
            case MATCH_FINISHED -> {
                if (varStatus == VarStatus.IN_PROGRESS || event.minute() != 15) {
                    throw new InvalidMatchEventException("Finish requires minute 15 and a resolved VAR review");
                }
            }
        }
        var events = copy ? new HashMap<>(processedEvents) : processedEvents;
        events.put(event.eventId(), event);
        return new MatchState(matchId, homeTeamId, awayTeamId, events, annulled, status, event.eventType() == EventType.MATCH_FINISHED, copy);
    }

    /** One mutable, private replay buffer; every event uses the same domain validations. */
    public static Rebuilder rebuilder(String matchId, String homeTeamId, String awayTeamId) {
        var initial = start(matchId, homeTeamId, awayTeamId);
        return new Rebuilder(new MatchState(initial.matchId, initial.homeTeamId, initial.awayTeamId,
                new HashMap<>(), new HashSet<>(), VarStatus.CLEAR, false, false));
    }

    public static final class Rebuilder {
        private MatchState state;
        private Rebuilder(MatchState state) { this.state = state; }
        public void accept(MatchEvent event) {
            if (state == null) throw new IllegalStateException("Replay already finished");
            state = state.apply(event, false);
        }
        public MatchState finish() {
            if (state == null) throw new IllegalStateException("Replay already finished");
            var result = new MatchState(state.matchId, state.homeTeamId, state.awayTeamId,
                    state.processedEvents, state.annulledGoalIds, state.varStatus, state.finished);
            state = null;
            return result;
        }
    }

    private MatchEvent activeGoal(String reference) {
        var goal = processedEvents.get(reference);
        if (goal == null) {
            throw new InvalidMatchEventException(InvalidMatchEventException.Reason.WAITING_REFERENCE, "Reference has not been accepted in this match");
        }
        if (goal.eventType() != EventType.GOAL) {
            throw new InvalidMatchEventException(InvalidMatchEventException.Reason.REFERENCE_NOT_GOAL, "Reference must identify a GOAL");
        }
        if (annulledGoalIds.contains(reference)) {
            throw new InvalidMatchEventException(InvalidMatchEventException.Reason.GOAL_ALREADY_ANNULLED, "Goal has already been annulled");
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
