package com.live_jgv.archiver.entrypoint;

import com.live_jgv.archiver.application.ArchiveService;
import com.live_jgv.archiver.domain.HistoryPage;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/history/matches")
public class HistoryController {
    private final ArchiveService service;
    public HistoryController(ArchiveService service) { this.service = service; }
    @GetMapping("/{matchId}/events")
    public HistoryPage events(@PathVariable @NotBlank @Size(max=512) String matchId,
                             @RequestParam(defaultValue="0") @Min(0) int page,
                             @RequestParam(defaultValue="50") @Min(1) @Max(200) int size) {
        return service.history(matchId, page, size);
    }
}
