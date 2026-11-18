package com.live_jgv.worker.academic;

import com.live_jgv.worker.domain.TransportMessage;
import java.util.Optional;

public interface AcademicCommandStore {
    enum Outcome { RECORDED, DUPLICATE, IDENTITY_CONFLICT, QUARANTINED }
    record Result(String commandId,String matchId,String sourceEventId,String commandType,String simulatedStatus,
                  String orderingStatus,long deliveryAttempts) { }
    Outcome record(AcademicCommand command,TransportMessage original);
    Outcome quarantine(TransportMessage original,String reason);
    Optional<Result> find(String commandId);
}
