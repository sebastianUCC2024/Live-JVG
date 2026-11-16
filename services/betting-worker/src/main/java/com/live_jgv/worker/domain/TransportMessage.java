package com.live_jgv.worker.domain;

/** Raw Rabbit evidence; delivery tags are intentionally not used as business identities. */
public record TransportMessage(String sourceId,String messageId,String exchange,String routingKey,byte[] body,String headers) {
    public TransportMessage {
        if(sourceId==null||sourceId.isBlank()||exchange==null||routingKey==null||headers==null) throw new IllegalArgumentException("Transport metadata required");
        body=body==null?null:body.clone();
    }
    @Override public byte[] body(){return body==null?null:body.clone();}
}
