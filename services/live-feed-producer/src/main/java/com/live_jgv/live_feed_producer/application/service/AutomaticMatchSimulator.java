package com.live_jgv.live_feed_producer.application.service;

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
    private int nextMatchIndex = 0;

    private static class Progress {
        int step = 0;
        String lastGoalId;
    }

    public AutomaticMatchSimulator(
            MatchSimulationService simulator,
            SimulatorMatchCatalog catalog,
            @Value("${simulator.auto-enabled:false}") boolean enabled) {

        this.simulator = simulator;
        this.enabled = enabled;
        this.matches = List.copyOf(catalog.getMatches());
        this.progress = new Progress[matches.size()];

        for (int i = 0; i < progress.length; i++) {
            progress[i] = new Progress();
        }

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("At least one match is required");
        }
    }

    @Scheduled(fixedDelayString = "${simulator.auto-interval-ms:15000}")
    public synchronized void generateEvent() {
        if (!enabled) return;

        int index = nextMatchIndex;
        var match = matches.get(index);
        var state = progress[index];

        if (state.step >= 80) {
            nextMatchIndex = (index + 1) % matches.size();
            return;
        }

        int current = state.step;
        String id = UUID.randomUUID().toString();

        EventType type;
        String team = null;
        String reference = null;


        // Different match patterns, deterministic and repeatable.
        // Every VAR_CHECK is followed by a resolution event.
        int pattern = Math.floorMod(index, 6);
        int phase = current % 6;

        switch (phase) {
            case 0 -> {
                type = EventType.GOAL;
                team = (pattern % 3 == 0)
                    ? match.awayTeamId() : match.homeTeamId();
            }
            case 1 -> {
                type = EventType.GOAL;
                team = (pattern % 2 == 0)
                    ? match.homeTeamId() : match.awayTeamId();
            }
            case 2 -> {
                type = EventType.VAR_CHECK;
                reference = state.lastGoalId;
            }
            case 3 -> {
                if (pattern % 3 == 0) {
                    type = EventType.GOAL_ANNULLED;
                    reference = state.lastGoalId;
                } else {
                    type = EventType.MATCH_CONTINUES;
                }
            }
            case 4 -> {
                type = EventType.GOAL;
                team = (pattern % 2 == 0)
                    ? match.awayTeamId() : match.homeTeamId();
            }
            default -> type = EventType.MATCH_CONTINUES;
        }

        try {
            simulator.publish(
                match.matchId(), id, type, team,
                Math.min(130, 10 + current),
                Instant.now(), reference
            ).join();

            if (type == EventType.GOAL) {
                state.lastGoalId = id;
            }

            state.step++;

            System.out.println("AUTO EVENT: match="
                + match.matchId() + ", event=" + type);

        } catch (Exception e) {
            System.err.println("AUTO EVENT FAILED: match="
                + match.matchId() + ", error=" + e.getMessage());
        }

        nextMatchIndex = (index + 1) % matches.size();
    }
}
