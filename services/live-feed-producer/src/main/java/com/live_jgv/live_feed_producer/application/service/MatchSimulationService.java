package com.live_jgv.live_feed_producer.application.service;

import com.live_jgv.live_feed_producer.config.SimulatorMatchCatalog;
import com.live_jgv.live_feed_producer.domain.model.*;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class MatchSimulationService {

    private final MatchEventPublisher publisher;
    private final Map<String, SimulatorMatchCatalog.Match> matches;

    @Autowired
    public MatchSimulationService(
            MatchEventPublisher publisher,
            SimulatorMatchCatalog catalog) {
        this.publisher = Objects.requireNonNull(publisher);
        this.matches = validateCatalog(catalog.getMatches());
    }

    // Backwards compatibility with existing tests and integrations.
    public MatchSimulationService(
            MatchEventPublisher publisher,
            String matchId,
            String home,
            String away) {
        this(publisher, singleMatch(matchId, home, away));
    }

    private static SimulatorMatchCatalog singleMatch(
            String matchId, String home, String away) {
        SimulatorMatchCatalog catalog = new SimulatorMatchCatalog();
        catalog.setMatches(List.of(
                new SimulatorMatchCatalog.Match(matchId, home, away)));
        return catalog;
    }

    private static Map<String, SimulatorMatchCatalog.Match> validateCatalog(
            List<SimulatorMatchCatalog.Match> definitions) {

        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one simulator match must be configured");
        }

        Map<String, SimulatorMatchCatalog.Match> result =
                new LinkedHashMap<>();

        for (var match : definitions) {
            if (match == null) {
                throw new IllegalArgumentException("Null match definition");
            }

            requireIdentifier(match.matchId(), "matchId");
            requireIdentifier(match.homeTeamId(), "homeTeamId");
            requireIdentifier(match.awayTeamId(), "awayTeamId");

            if (match.homeTeamId().equals(match.awayTeamId())) {
                throw new IllegalArgumentException(
                        "Home and away teams must differ");
            }

            if (result.putIfAbsent(match.matchId(), match) != null) {
                throw new IllegalArgumentException(
                        "Duplicate matchId: " + match.matchId());
            }
        }

        return Collections.unmodifiableMap(result);
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be null or blank");
        }
    }

    public Set<String> registeredMatchIds() {
        return matches.keySet();
    }

    public CompletableFuture<MatchEvent> publish(
            String match, String id, EventType type, String team,
            int minute, Instant timestamp, String reference) {

        OutgoingReferencePolicy.validate(reference);

        var definition = matches.get(match);
        if (definition == null && match.matches("[A-Za-z0-9_.-]+[.][a-f0-9-]{36}")) {
            definition = matches.get(match.substring(0, match.lastIndexOf('.')));
        }

        if (definition == null) {
            throw new IllegalArgumentException(
                    "Partido no registrado en el simulador");
        }

        if (team != null
                && !team.equals(definition.homeTeamId())
                && !team.equals(definition.awayTeamId())) {
            throw new IllegalArgumentException(
                    "Equipo ajeno al partido");
        }

        if ((id == null) != (timestamp == null)) {
            throw new IllegalArgumentException(
                    "event_id y timestamp deben enviarse juntos");
        }

        var event = id == null
                ? MatchEvent.create(match, type, team, minute,
                        Instant.now(), reference)
                : new MatchEvent(id, match, type, team,
                        minute, timestamp, reference);

        try {
            return publisher.publish(event).handle((ignored, failure) -> {
                if (failure != null) {
                    throw publicationFailure(event, failure);
                }
                return event;
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    publicationFailure(event, failure));
        }
    }

    private static EventPublicationException publicationFailure(
            MatchEvent event, Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return new EventPublicationException(event, failure);
    }
}
