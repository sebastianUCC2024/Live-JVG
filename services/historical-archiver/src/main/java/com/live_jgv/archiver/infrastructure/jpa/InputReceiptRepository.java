package com.live_jgv.archiver.infrastructure.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface InputReceiptRepository extends JpaRepository<InputReceiptEntity,InputReceiptId> {
    @Modifying
    @Query(value="""
        INSERT INTO archiver.input_receipt(source_id,topic,partition_id,record_offset,broker_timestamp,
            original_key,original_payload,original_headers,outcome)
        VALUES (:source,:topic,:partition,:offset,:timestamp,:key,:payload,:headers,'PENDING')
        ON CONFLICT DO NOTHING
        """,nativeQuery=true)
    int insertPending(@Param("source") String source,@Param("topic") String topic,@Param("partition") int partition,
                      @Param("offset") long offset,@Param("timestamp") long timestamp,@Param("key") byte[] key,
                      @Param("payload") byte[] payload,@Param("headers") String headers);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InputReceiptEntity r where r.id=:id")
    Optional<InputReceiptEntity> lock(@Param("id") InputReceiptId id);
}
