package com.live_jgv.dashboard;
import com.live_jgv.dashboard.infrastructure.jdbc.ScoreProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.dashboard.application.port.out.MatchSnapshotPublisher;
import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import org.junit.jupiter.api.*;import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;import org.springframework.transaction.support.*;
import org.springframework.amqp.core.*;import java.util.*;import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class SourceSequenceProjectionTest {
 JdbcTemplate jdbc;PlatformTransactionManager manager;ScoreProjection projection;
 String payload="{\"match_id\":\"m\",\"event_id\":\"goal\",\"home_score\":1,\"away_score\":0,\"event_type\":\"GOAL\",\"var_status\":\"CLEAR\",\"updated_at\":\"2026-11-17T00:00:00Z\",\"reference_event_id\":null}";
 @BeforeEach void setup(){jdbc=mock(JdbcTemplate.class);manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());when(jdbc.queryForMap(anyString(),eq("m"))).thenReturn(Map.of("home_team_id","h","away_team_id","a","home_name","Home","away_name","Away"));
 projection=spy(new ScoreProjection(jdbc,manager,new ObjectMapper(),mock(MatchSnapshotPublisher.class),"m","h","a","Home","Away",false,true));doReturn(Optional.empty()).when(projection).snapshot("m");when(jdbc.queryForObject(contains("MAX(source_application_seq)"),eq(Long.class),eq("m"))).thenReturn(0L);when(jdbc.queryForObject(contains("source_application_seq IS NULL"),eq(Boolean.class),eq("m"))).thenReturn(false);clearInvocations(jdbc,manager);}
 Message message(Object seq){var props=new MessageProperties();if(seq!=null)props.setHeader("application_seq",seq);return new Message(payload.getBytes(StandardCharsets.UTF_8),props);}
 @Test void firstSourceSequenceCommitsInboxAndScoreTogether(){projection.receive(message(1L));verify(jdbc).update(contains("payload,source_application_seq"),eq("m"),eq("goal"),anyString(),eq(1L));verify(manager).commit(any());}
 @Test void unknownOldEventCannotRegressScore(){when(jdbc.queryForObject(contains("MAX(source_application_seq)"),eq(Long.class),eq("m"))).thenReturn(3L);assertThrows(InvalidScoreUpdateException.class,()->projection.receive(message(2L)));verify(jdbc,never()).update(anyString(),any(Object[].class));verify(manager).rollback(any());}
 @Test void sequenceGapIsNotGuessedFromTimestamp(){assertThrows(InvalidScoreUpdateException.class,()->projection.receive(message(3L)));verify(jdbc,never()).update(anyString(),any(Object[].class));}
 @Test void legacyRowsCannotBeSilentlyMixedWithSourceSequenceOne(){when(jdbc.queryForObject(contains("source_application_seq IS NULL"),eq(Boolean.class),eq("m"))).thenReturn(true);assertThrows(InvalidScoreUpdateException.class,()->projection.receive(message(1L)));verify(jdbc,never()).update(anyString(),any(Object[].class));verify(manager).rollback(any());}
 @Test void missingMalformedAndFloatingHeadersAreRejectedBeforeDatabase(){for(Object bad:new Object[]{null,"1",1.5d,0L,-1L})assertThrows(InvalidScoreUpdateException.class,()->projection.receive(message(bad)));verifyNoInteractions(jdbc,manager);}
 @Test void knownDuplicateCannotReplaceSnapshot(){when(jdbc.queryForList(contains("FROM dashboard.inbox WHERE"),eq("m"),eq("goal"))).thenReturn(List.of(Map.of("payload",payload,"source_application_seq",1L)));projection.receive(message(1L));verify(jdbc,never()).update(anyString(),any(Object[].class));verify(manager).commit(any());}
 @Test void reusedIdentityWithDifferentSequenceIsRejected(){when(jdbc.queryForList(contains("FROM dashboard.inbox WHERE"),eq("m"),eq("goal"))).thenReturn(List.of(Map.of("payload",payload,"source_application_seq",1L)));assertThrows(InvalidScoreUpdateException.class,()->projection.receive(message(2L)));verify(manager).rollback(any());}
}
