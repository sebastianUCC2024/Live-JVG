package com.live_jgv.notification.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.live_jgv.notification.application.NotificationStore;
import com.live_jgv.notification.domain.LiveAlert;
import com.live_jgv.notification.domain.ScoreUpdate;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alerts")
public final class AlertController {
    private final NotificationStore store;
    public AlertController(NotificationStore store) { this.store=store; }
    @GetMapping public ResponseEntity<AlertPageResponse> alerts(@RequestParam("match_id") String matchId,
            @RequestParam(value="after",required=false) String after,@RequestParam(defaultValue="50") int limit) {
        if (matchId.isBlank() || matchId.length()>1024 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid alert query");
        var page=store.query(matchId,after,limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AlertPageResponse(
                page.items().stream().map(AlertResponse::from).toList(),page.nextCursor(),page.hasMore()));
    }
    public record AlertPageResponse(List<AlertResponse> items,@JsonProperty("next_cursor") String nextCursor,@JsonProperty("has_more") boolean hasMore) { }
    /** Local REST DTO only; shared contracts/events/live-alert.schema.json remains unchanged. */
    public record AlertResponse(@JsonProperty("contract_version") String contractVersion,
            @JsonProperty("alert_id") String alertId,@JsonProperty("match_id") String matchId,
            @JsonProperty("alert_type") ScoreUpdate.EventType alertType,@JsonProperty("source_event_id") String sourceEventId,
            @JsonProperty("source_updated_at") String sourceUpdatedAt,@JsonProperty("created_at") String createdAt,
            @JsonProperty("reference_event_id") String referenceEventId,@JsonProperty("home_score") int homeScore,
            @JsonProperty("away_score") int awayScore,@JsonProperty("var_status") ScoreUpdate.VarStatus varStatus,LiveAlert.Content content) {
        static AlertResponse from(LiveAlert value) {
            var s=value.source();
            return new AlertResponse("1.0",value.alertId().toString(),s.matchId(),s.eventType(),s.eventId(),s.updatedAt().toString(),
                    value.createdAt().toString(),s.referenceEventId(),s.homeScore(),s.awayScore(),s.varStatus(),value.content());
        }
    }
}
