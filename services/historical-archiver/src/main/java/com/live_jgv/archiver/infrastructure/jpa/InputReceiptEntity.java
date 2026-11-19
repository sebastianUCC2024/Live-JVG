package com.live_jgv.archiver.infrastructure.jpa;

import com.live_jgv.archiver.domain.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;

@Entity
@Table(name="input_receipt", schema="archiver")
public class InputReceiptEntity {
    @EmbeddedId private InputReceiptId id;
    @Column(name="broker_timestamp", nullable=false, updatable=false) private long brokerTimestamp;
    @Column(name="original_key", columnDefinition="bytea", updatable=false) private byte[] originalKey;
    @Column(name="original_payload", columnDefinition="bytea", updatable=false) private byte[] originalPayload;
    @Column(name="original_headers", nullable=false, columnDefinition="text", updatable=false) private String originalHeaders;
    @Column(name="outcome", nullable=false, columnDefinition="text") private String outcome;
    @Column(name="match_id", columnDefinition="text") private String matchId;
    @Column(name="event_id", columnDefinition="text") private String eventId;
    @Column(name="reason_code", columnDefinition="text") private String reasonCode;
    @Column(name="received_at", nullable=false, insertable=false, updatable=false) private Instant receivedAt;
    protected InputReceiptEntity() { }
    public boolean hasOriginal(InputRecord record) {
        return brokerTimestamp==record.brokerTimestamp() && Arrays.equals(originalKey,record.key())
                && Arrays.equals(originalPayload,record.value()) && originalHeaders.equals(record.headers());
    }
    public ArchiveOutcome outcome() { return ArchiveOutcome.valueOf(outcome); }
    public void complete(ArchiveOutcome result,String match,String event,String reason) {
        outcome=result.name();matchId=match;eventId=event;reasonCode=reason;
    }
}
