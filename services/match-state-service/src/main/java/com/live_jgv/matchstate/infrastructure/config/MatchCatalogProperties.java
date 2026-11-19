package com.live_jgv.matchstate.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("match-state")
public class MatchCatalogProperties {
    private List<Definition> matches = new ArrayList<>();
    public List<Definition> getMatches() { return matches; }
    public void setMatches(List<Definition> matches) { this.matches = matches; }

    public record Definition(String matchId, String homeTeamId, String awayTeamId) { }
}
