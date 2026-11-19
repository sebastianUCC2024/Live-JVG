package com.live_jgv.betting.infrastructure.web;

import com.live_jgv.betting.application.SuspensionService;
import com.live_jgv.betting.domain.MarketState;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/betting/matches")
public class StatusController {
    private final SuspensionService service;
    public StatusController(SuspensionService service) { this.service=service; }
    public record StatusView(String matchId,MarketState.Status status,long version,String lastEventId,
                             Instant updatedAt,int pendingReviews,boolean betsAllowed,boolean reliable) {
        public static StatusView from(MarketState state) {
            return new StatusView(state.matchId(),state.status(),state.version(),state.lastEventId(),state.updatedAt(),
                    state.pendingReviews().size(),state.betsAllowed(),state.status()!=MarketState.Status.UNKNOWN&&!state.inconsistent());
        }
    }
    @GetMapping("/{matchId}/status")
    public StatusView status(@PathVariable @NotBlank @Size(max=512) String matchId) { return StatusView.from(service.status(matchId)); }
}
