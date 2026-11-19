package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.*;
import java.util.Optional;

public final class WorkerService {
    private final WorkerStore store;private final CommandDecoder decoder;
    private final CommandPreparation preparation;
    public WorkerService(WorkerStore store,CommandDecoder decoder,CommandPreparation preparation){
        this.store=store;this.decoder=decoder;this.preparation=java.util.Objects.requireNonNull(preparation);
    }
    /** Procesa el comando interno sin publicar ni activar el consumidor RabbitMQ. */
    public WorkerStore.Outcome submit(SimulatedCommand command){
        var input=preparation.prepare(command);
        return store.handle(input.message(),input.decoded());
    }
    public WorkerStore.Outcome receive(TransportMessage message){return store.handle(message,decoder.decode(message));}
    public WorkerStore.Outcome reject(TransportMessage message,String reason){return store.handle(message,DecodedCommand.invalid(reason));}
    public Optional<Operation> find(String id){
        if(id==null||id.isBlank())throw new IllegalArgumentException("Operation identity required");return store.find(id);
    }
}
