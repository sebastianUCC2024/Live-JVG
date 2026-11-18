package com.live_jgv.live_feed_producer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "simulator")
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
