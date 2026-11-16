package com.live_jgv.betting.domain;

import java.time.Instant;
import java.util.*;

/** Fail-closed aggregate: sporting continuation alone is not proof of review closure. */
public record MarketState(String matchId, Status status, long version, String lastEventId,
                          Instant updatedAt, boolean inconsistent, Map<String,PendingReview> pendingReviews) {
    public enum Status { OPEN, SUSPENDED, UNKNOWN }
    public record PendingReview(String reviewId, String referenceEventId, long openedVersion) { }
    public MarketState {
        if (matchId == null || matchId.isBlank() || version < 0) throw new IllegalArgumentException("Invalid market");
        Objects.requireNonNull(status); pendingReviews=Map.copyOf(pendingReviews);
        if (status == Status.OPEN && (inconsistent || !pendingReviews.isEmpty() || version == 0 || updatedAt == null || lastEventId == null))
            throw new IllegalStateException("Open market cannot have unresolved uncertainty");
    }
    public static MarketState unknown(String match) { return new MarketState(match,Status.UNKNOWN,0,null,null,false,Map.of()); }
    public boolean betsAllowed() { return status == Status.OPEN && !inconsistent && pendingReviews.isEmpty(); }
    public MarketState apply(SportEvent event, Instant now) {
        if (!matchId.equals(event.matchId())) throw new IllegalArgumentException("Match mismatch");
        var pending=new LinkedHashMap<>(pendingReviews);var next=status;boolean uncertain=inconsistent;
        long revision=Math.addExact(version,1);
        switch (event.eventType()) {
            case VAR_CHECK -> {
                pending.put(event.eventId(),new PendingReview(event.eventId(),event.referenceEventId(),revision));
                next=Status.SUSPENDED;
            }
            case GOAL_ANNULLED -> {
                next=Status.SUSPENDED;
                // A late or uncorrelated decision cannot reopen the market through another review.
                if (pending.values().stream().noneMatch(review -> Objects.equals(review.referenceEventId(),event.referenceEventId()))) uncertain=true;
            }
            case GOAL, MATCH_CONTINUES -> { /* No authority to open, close or correlate a review. */ }
        }
        return new MarketState(matchId,next,revision,event.eventId(),now,uncertain,pending);
    }
    public MarketState conflict(Instant now) {
        if (inconsistent && status == Status.UNKNOWN) return this;
        return new MarketState(matchId,Status.UNKNOWN,Math.addExact(version,1),lastEventId,now,true,pendingReviews);
    }
    public MarketState close(ReviewClosure command, SportEvent continuation, long continuationVersion, Instant now) {
        if (!matchId.equals(command.matchId()) || command.expectedVersion()!=version)
            throw new IllegalStateException("Market revision changed");
        var review=pendingReviews.get(command.reviewId());
        if (review==null || inconsistent || continuation==null || !continuation.matchId().equals(matchId)
                || continuation.eventType()!=SportEvent.EventType.MATCH_CONTINUES
                || !continuation.eventId().equals(command.continuationEventId())
                || continuationVersion<=review.openedVersion())
            throw new IllegalArgumentException("Unambiguous subsequent continuation is required");
        var pending=new LinkedHashMap<>(pendingReviews);pending.remove(command.reviewId());
        return new MarketState(matchId,pending.isEmpty()?Status.OPEN:Status.SUSPENDED,
                Math.addExact(version,1),continuation.eventId(),now,false,pending);
    }
}
