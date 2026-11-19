package com.live_jgv.worker.academic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;
import java.util.TreeMap;

/** Acción académica V1: el resultado simulado no autoriza apuestas. */
public record AcademicCommand(int schemaVersion,String commandId,String matchId,String sourceEventId,
                              CommandType commandType,Instant timestamp) {
    public enum CommandType { SUSPEND_BETS, RESUME_BETS }
    public AcademicCommand {
        if(schemaVersion!=1)throw new IllegalArgumentException("Unsupported academic schema version");
        for(String id:new String[]{commandId,matchId,sourceEventId})
            if(id==null||id.isBlank())throw new IllegalArgumentException("Academic identity required");
        Objects.requireNonNull(commandType);Objects.requireNonNull(timestamp);
    }
    public String json() {
        var values=new TreeMap<String,Object>();
        values.put("schema_version",schemaVersion);values.put("command_id",commandId);
        values.put("match_id",matchId);values.put("source_event_id",sourceEventId);
        values.put("command_type",commandType.name());values.put("timestamp",timestamp.toString());
        try{return new ObjectMapper().writeValueAsString(values);}
        catch(JsonProcessingException impossible){throw new IllegalStateException("Cannot encode academic command",impossible);}
    }
}
