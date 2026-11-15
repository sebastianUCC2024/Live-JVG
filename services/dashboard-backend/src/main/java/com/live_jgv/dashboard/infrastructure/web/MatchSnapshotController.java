package com.live_jgv.dashboard.infrastructure.web;

import com.live_jgv.dashboard.domain.port.in.GetMatchSnapshot;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/matches")
public class MatchSnapshotController {
    private final GetMatchSnapshot query;

    public MatchSnapshotController(GetMatchSnapshot query) {
        this.query = query;
    }

    @GetMapping("/{matchId}/score")
    public ResponseEntity<MatchSnapshotResponse> score(@PathVariable String matchId) {
        return query.find(matchId)
                .map(snapshot -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(MatchSnapshotResponse.from(snapshot)))
                .orElseGet(() -> ResponseEntity.status(404).cacheControl(CacheControl.noStore()).build());
    }
}
