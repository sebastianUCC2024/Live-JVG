package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.*;
import java.util.Optional;

public interface WorkerStore {
    enum Outcome { PROCESSED, DUPLICATE, IDENTITY_CONFLICT, QUARANTINED }
    Outcome handle(TransportMessage message,DecodedCommand decoded);
    Optional<Operation> find(String operationId);
}
