package com.live_jgv.worker.infrastructure;

import com.live_jgv.worker.application.CommandPreparation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.worker.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.TreeMap;

/** Serializa el comprobante interno sin modificar su identidad persistida. */
public final class JsonDomainCommandPreparation implements CommandPreparation {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    @Override public Input prepare(SimulatedCommand command){
        Objects.requireNonNull(command,"Simulated command required");
        var fields=new TreeMap<String,Object>();
        fields.put("schema_version",1);fields.put("command_id",command.commandId());
        fields.put("operation_id",command.operationId());fields.put("match_id",command.matchId());
        fields.put("simulated_user_id",command.simulatedUserId());fields.put("timestamp",command.timestamp().toString());
        try {
            String canonical=MAPPER.writeValueAsString(fields);
            var receipt=new TransportMessage("internal/domain",null,"","",canonical.getBytes(StandardCharsets.UTF_8),"{}");
            return new Input(receipt,new DecodedCommand(command,canonical,null));
        } catch(JsonProcessingException failure){
            throw new IllegalStateException("Cannot preserve domain command",failure);
        }
    }
}
