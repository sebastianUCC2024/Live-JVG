package com.live_jgv.worker.academic;

import com.live_jgv.worker.domain.TransportMessage;
import java.util.Objects;

/** Procesa comandos académicos sin conceder autoridad sobre el mercado. */
public final class AcademicCommandHandler {
    private final AcademicCommandStore store;
    private final AcademicCommandCodec codec;
    public AcademicCommandHandler(AcademicCommandStore store,AcademicCommandCodec codec){
        this.store=Objects.requireNonNull(store);this.codec=Objects.requireNonNull(codec);
    }
    public AcademicCommandStore.Outcome receive(TransportMessage message) {
        final AcademicCommand command;
        try{command=codec.decode(message.body());}
        catch(IllegalArgumentException invalid){return store.quarantine(message,"INVALID_ACADEMIC_COMMAND");}
        if(message.messageId()!=null&&!message.messageId().equals(command.commandId()))
            return store.quarantine(message,"MESSAGE_ID_MISMATCH");
        return store.record(command,message);
    }
}
