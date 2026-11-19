package com.live_jgv.live_feed_producer.infrastructure.scheduling;

import com.live_jgv.live_feed_producer.application.service.MatchSimulationService;

import com.live_jgv.live_feed_producer.config.SimulatorMatchCatalog;
import com.live_jgv.live_feed_producer.domain.model.EventType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class AutomaticMatchSimulator {

    private final MatchSimulationService simulator;
    private final boolean enabled;
    private final List<SimulatorMatchCatalog.Match> matches;
    private final Progress[] progress;
    private UUID roundId;
    private static final int TICKS_PER_MINUTE = 4;
    private static final int FINAL_MINUTE = 15;

    private static class Progress {
        int step = 0;
        UUID eventId;
        Instant timestamp;
        String lastGoalId;
    }

    public AutomaticMatchSimulator(
            MatchSimulationService simulator,
            SimulatorMatchCatalog catalog,
            @Value("${simulator.auto-enabled:false}") boolean enabled) {

        this.simulator = simulator;
        this.enabled = enabled;
        this.matches = catalog.getMatches().stream().filter(match -> match.matchId().matches("demo-col-v4-00[1-6]")).toList();
        this.progress = new Progress[matches.size()];

        for (int i = 0; i < progress.length; i++) {
            progress[i] = new Progress();
        }

    }

    @Scheduled(fixedRate = 15_000)
    public synchronized void generateEvent() {
        if (!enabled || roundId == null) return;

        for (int index = 0; index < matches.size(); index++) {
            generateForMatch(index);
        }
    }

    public synchronized List<String> restart(UUID requestedRound) {
        if (!enabled || matches.isEmpty()) throw new IllegalStateException("La simulación automática está deshabilitada");
        if (!requestedRound.equals(roundId)) {
            roundId = requestedRound;
            for (int index = 0; index < progress.length; index++) progress[index] = new Progress();
        }
        return matches.stream().map(match -> roundMatchId(match, roundId)).toList();
    }

    private static String roundMatchId(SimulatorMatchCatalog.Match match, UUID round) {
        return match.matchId() + "." + round;
    }

    private void generateForMatch(int index) {
        var match = matches.get(index);
        var state = progress[index];
        int current = state.step;
        if (current > FINAL_MINUTE * TICKS_PER_MINUTE) return;

        if (state.eventId == null) {
            state.eventId = UUID.randomUUID();
            state.timestamp = Instant.now();
        }
        String id = state.eventId.toString();

        EventType type;
        String team = null;
        String reference = null;


        // Spread scoring and reviews through the match while still publishing
        // a snapshot on every scheduled tick.
        int pattern = Math.floorMod(index, 6);
        int phase = current - pattern;
        int finalTick = FINAL_MINUTE * TICKS_PER_MINUTE;
        boolean awayAdvantage = pattern == 1 || pattern == 4;
        if (current == finalTick) {
            type = EventType.MATCH_FINISHED;
        } else {
            switch (phase) {
                case 5, 36 -> {
                    type = EventType.GOAL;
                    team = awayAdvantage ? match.awayTeamId() : match.homeTeamId();
                }
                case 13, 45 -> {
                    type = EventType.GOAL;
                    team = awayAdvantage ? match.homeTeamId() : match.awayTeamId();
                }
                case 17, 49 -> {
                    type = EventType.VAR_CHECK;
                    reference = state.lastGoalId;
                }
                case 18, 50 -> {
                    if ((pattern % 2 == 0) == (phase == 18)) {
                        type = EventType.GOAL_ANNULLED;
                        reference = state.lastGoalId;
                    } else {
                        type = EventType.MATCH_CONTINUES;
                    }
                }
                case 25 -> {
                    type = pattern == 3 || pattern == 5 ? EventType.GOAL : EventType.MATCH_CONTINUES;
                    if (type == EventType.GOAL) team = match.homeTeamId();
                }
                case 53 -> {
                    type = pattern == 2 || pattern == 4 || pattern == 5 ? EventType.GOAL : EventType.MATCH_CONTINUES;
                    if (type == EventType.GOAL) team = match.awayTeamId();
                }
                default -> type = EventType.MATCH_CONTINUES;
            }
        }

        try {
            simulator.publish(
                roundMatchId(match, roundId), id, type, team,
                current / TICKS_PER_MINUTE,
                state.timestamp, reference
            ).join();

            if (type == EventType.GOAL) {
                state.lastGoalId = id;
            }

            state.step++;
            state.eventId = null;

            System.out.println("AUTO EVENT: match="
                + roundMatchId(match, roundId) + ", event=" + type);

        } catch (Exception e) {
            System.err.println("AUTO EVENT FAILED: match="
                + match.matchId() + ", error=" + e.getMessage());
        }

    }
}
