package com.live_jgv.archiver.infrastructure.jpa;

import com.live_jgv.archiver.domain.HistoryPage;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;

/** Mapea la tabla existente sin acoplar el dominio a JPA ni habilitar borrados. */
@Entity
@Table(name="archived_event", schema="archiver")
@Immutable
public class ArchivedEventEntity {
    @Id @Column(name="seq") private Long sequence;
    @Column(name="event_id", nullable=false, columnDefinition="text") private String eventId;
    @Column(name="match_id", nullable=false, columnDefinition="text") private String matchId;
    @Column(name="event_type", nullable=false, columnDefinition="text") private String eventType;
    @Column(name="team_id", columnDefinition="text") private String teamId;
    @Column(name="minute", nullable=false) private int minute;
    @Column(name="event_timestamp", nullable=false, columnDefinition="text") private String eventTimestamp;
    @Column(name="reference_event_id", columnDefinition="text") private String referenceEventId;
    // Usa bytea; @Lob cambiaría almacenamiento y permisos en PostgreSQL.
    @Column(name="original_payload", nullable=false, columnDefinition="bytea") private byte[] originalPayload;
    @Column(name="canonical_payload", nullable=false, columnDefinition="text") private String canonicalPayload;
    @Column(name="received_at", nullable=false, insertable=false, updatable=false) private Instant receivedAt;
    protected ArchivedEventEntity() { }
    public String canonicalPayload() { return canonicalPayload; }
    public HistoryPage.Entry historyEntry() {
        return new HistoryPage.Entry(sequence,eventId,matchId,eventType,teamId,minute,
                Instant.parse(eventTimestamp),referenceEventId,receivedAt);
    }
}
