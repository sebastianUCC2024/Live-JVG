package com.live_jgv.archiver.infrastructure.jpa;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class InputReceiptId implements Serializable {
    private static final long serialVersionUID = 1L;
    @Column(name="source_id", columnDefinition="text") private String sourceId;
    @Column(name="topic", columnDefinition="text") private String topic;
    @Column(name="partition_id") private int partition;
    @Column(name="record_offset") private long offset;
    protected InputReceiptId() { }
    public InputReceiptId(String sourceId,String topic,int partition,long offset) {
        this.sourceId=sourceId;this.topic=topic;this.partition=partition;this.offset=offset;
    }
    @Override public boolean equals(Object other) {
        return other instanceof InputReceiptId id && partition==id.partition && offset==id.offset
                && Objects.equals(sourceId,id.sourceId) && Objects.equals(topic,id.topic);
    }
    @Override public int hashCode() { return Objects.hash(sourceId,topic,partition,offset); }
}
