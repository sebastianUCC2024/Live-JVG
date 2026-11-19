package com.live_jgv.live_feed_producer.application.model;

import java.util.ArrayList;
import java.util.List;

/** Match definitions supplied by composition; contains no framework binding annotations. */
public class SimulatorMatchCatalog {

    private List<Match> matches = new ArrayList<>();

    public List<Match> getMatches() {
        return matches;
    }

    public void setMatches(List<Match> matches) {
        this.matches = matches;
    }

    public record Match(
        String matchId,
        String homeTeamId,
        String awayTeamId
    ) {}
}
