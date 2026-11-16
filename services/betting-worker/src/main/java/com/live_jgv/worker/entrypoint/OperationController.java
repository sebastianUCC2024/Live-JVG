package com.live_jgv.worker.entrypoint;

import com.live_jgv.worker.application.WorkerService;
import com.live_jgv.worker.domain.Operation;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;

@RestController
@RequestMapping("/api/betting/operations")
public class OperationController {
    private final WorkerService service;
    public OperationController(WorkerService service){this.service=service;}
    public record OperationView(String operationId,String commandId,String matchId,Operation.State state,String reason,
                                long version,Instant timestamp,Instant receivedAt,Instant updatedAt){
        public static OperationView from(Operation o){return new OperationView(o.operationId(),o.commandId(),o.matchId(),o.state(),o.reason(),o.version(),o.timestamp(),o.receivedAt(),o.updatedAt());}
    }
    @GetMapping("/{operationId}")
    public OperationView find(@PathVariable @NotBlank @Size(max=512) String operationId){
        return service.find(operationId).map(OperationView::from).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Operation not found"));
    }
}
