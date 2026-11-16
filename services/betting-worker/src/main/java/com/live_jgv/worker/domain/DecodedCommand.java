package com.live_jgv.worker.domain;

public record DecodedCommand(SimulatedCommand command,String canonicalPayload,String error) {
    public DecodedCommand {
        boolean valid=command!=null&&canonicalPayload!=null&&error==null;
        boolean invalid=command==null&&canonicalPayload==null&&error!=null;
        if(!valid&&!invalid)throw new IllegalArgumentException("Invalid decoding result");
    }
    public static DecodedCommand invalid(String reason){return new DecodedCommand(null,null,reason);}
}
