package com.live_jgv.archiver.infrastructure.jpa;

import com.live_jgv.archiver.application.ArchiveStore;
import com.live_jgv.archiver.domain.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

/**
 * Maps raw input and validated domain events to JPA receipts and archived-event entities.
 * One write transaction claims the source position, locks its receipt and records the outcome.
 * Repeated positions must retain identical bytes; conflicting content fails instead of replacing
 * accepted history. Read transactions use repeatable-read snapshots for coherent pagination.
 * Native inserts arbitrate races while domain models remain independent of Hibernate.
 */
public final class JpaArchiveStore implements ArchiveStore {
    private final ArchivedEventRepository events;
    private final InputReceiptRepository receipts;
    private final TransactionTemplate write, read;
    public JpaArchiveStore(ArchivedEventRepository events,InputReceiptRepository receipts,PlatformTransactionManager manager) {
        if (!(manager instanceof JpaTransactionManager)) throw new IllegalArgumentException("JPA archive requires one JpaTransactionManager");
        this.events=Objects.requireNonNull(events);this.receipts=Objects.requireNonNull(receipts);
        write=new TransactionTemplate(manager);
        read=new TransactionTemplate(manager);read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public ArchiveOutcome save(InputRecord record,DecodedInput input) {
        return Objects.requireNonNull(write.execute(status -> archive(record,input)));
    }
    private ArchiveOutcome archive(InputRecord record,DecodedInput input) {
        int claimed=receipts.insertPending(record.sourceId(),record.topic(),record.partition(),record.offset(),
                record.brokerTimestamp(),record.key(),record.value(),record.headers());
        if(claimed!=0 && claimed!=1) throw new IllegalStateException("Invalid receipt insert count");
        var id=new InputReceiptId(record.sourceId(),record.topic(),record.partition(),record.offset());
        var receipt=receipts.lock(id).orElseThrow(()->new IllegalStateException("Archive receipt missing"));
        if(claimed==0) {
            if(!receipt.hasOriginal(record)) throw new IllegalStateException("Source position reused with different content; change source identity after review");
            return receipt.outcome();
        }
        ArchiveOutcome outcome;String reason=null,eventId=null,matchId=null;
        if(input.reason()!=null) { outcome=ArchiveOutcome.QUARANTINED;reason=input.reason().name(); }
        else {
            var event=input.event();eventId=event.eventId();matchId=event.matchId();
            int inserted=events.insertIfAbsent(eventId,matchId,event.eventType().name(),event.teamId(),event.minute(),
                    event.timestamp().toString(),event.referenceEventId(),record.value(),input.canonicalPayload());
            if(inserted==1) outcome=ArchiveOutcome.STORED;
            else if(inserted==0) {
                var previous=events.findByMatchIdAndEventId(matchId,eventId).orElseThrow(()->new IllegalStateException("Archive identity missing"));
                outcome=input.canonicalPayload().equals(previous.canonicalPayload())?ArchiveOutcome.DUPLICATE:ArchiveOutcome.IDENTITY_CONFLICT;
                if(outcome==ArchiveOutcome.IDENTITY_CONFLICT) reason="EVENT_ID_REUSED";
            } else throw new IllegalStateException("Invalid event insert count");
        }
        receipt.complete(outcome,matchId,eventId,reason);
        // Managed entity dirty checking writes only mutable audit fields. Flush inside the same transaction;
        // constraint/storage errors propagate before Kafka's listener is allowed to return successfully.
        receipts.flush();
        return outcome;
    }
    @Override public HistoryPage find(String match,int page,int size) {
        return Objects.requireNonNull(read.execute(status -> {
            long count=events.countByMatchId(match);
            long offset=Math.multiplyExact((long)page,size);
            var entities=offset<=Integer.MAX_VALUE?events.history(match,PageRequest.of(page,size)):events.historyAtOffset(match,offset,size);
            var rows=entities.stream().map(ArchivedEventEntity::historyEntry).toList();
            return new HistoryPage(rows,page,size,count,count/size+(count%size==0?0:1));
        }));
    }
}
