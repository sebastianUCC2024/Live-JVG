package com.live_jgv.archiver.infrastructure.jpa;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;

public interface ArchivedEventRepository extends JpaRepository<ArchivedEventEntity,Long> {
    @Modifying
    @Query(value="""
        INSERT INTO archiver.archived_event(event_id,match_id,event_type,team_id,minute,event_timestamp,
            reference_event_id,original_payload,canonical_payload)
        VALUES (:event,:match,:type,:team,:minute,:timestamp,:reference,:payload,:canonical)
        ON CONFLICT (match_id,event_id) DO NOTHING
        """,nativeQuery=true)
    int insertIfAbsent(@Param("event") String event,@Param("match") String match,@Param("type") String type,
                       @Param("team") String team,@Param("minute") int minute,@Param("timestamp") String timestamp,
                       @Param("reference") String reference,@Param("payload") byte[] payload,@Param("canonical") String canonical);
    Optional<ArchivedEventEntity> findByMatchIdAndEventId(String matchId,String eventId);
    long countByMatchId(String matchId);
    @Query("select e from ArchivedEventEntity e where e.matchId=:match order by e.sequence asc")
    List<ArchivedEventEntity> history(@Param("match") String match, Pageable page);
    // Conserva offsets long de la API aunque JPA utilice int.
    @Query(value="SELECT * FROM archiver.archived_event WHERE match_id=:match ORDER BY seq ASC LIMIT :size OFFSET :offset",nativeQuery=true)
    List<ArchivedEventEntity> historyAtOffset(@Param("match") String match,@Param("offset") long offset,@Param("size") int size);
}
