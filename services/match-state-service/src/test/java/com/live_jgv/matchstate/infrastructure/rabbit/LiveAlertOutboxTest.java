package com.live_jgv.matchstate.infrastructure.rabbit;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.infrastructure.jdbc.JdbcMatchStateStore;
import com.live_jgv.matchstate.infrastructure.config.LiveAlertPublicationConfiguration;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class LiveAlertOutboxTest {
    JdbcTemplate jdbc;PlatformTransactionManager manager;ObjectMapper mapper;
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class);manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(x->new SimpleTransactionStatus());
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    @Test void bothDestinationsArePersistedWithTheAcceptedGoalInOneCommit() throws Exception {
        var before=MatchState.start("match","home","away");
        var event=new MatchEvent("goal","match",EventType.GOAL,"home",10,Instant.EPOCH,null);
        var update=new ScoreUpdate("match",1,0,"goal",EventType.GOAL,Instant.EPOCH,VarStatus.CLEAR,null);
        var store=new JdbcMatchStateStore(jdbc,new TransactionTemplate(manager),mapper,"live_updates","score.{matchId}",true);
        assertTrue(store.compareAndSet(new LoadedMatch(before,0),before.apply(event),update));
        var order=inOrder(jdbc,manager);
        order.verify(jdbc).update(startsWith("INSERT INTO match_state.accepted_event"),any(Object[].class));
        order.verify(jdbc).update(startsWith("INSERT INTO match_state.score_outbox"),any(Object[].class));
        order.verify(jdbc).update(startsWith("INSERT INTO match_state.alert_outbox"),any(Object[].class));
        order.verify(manager).commit(any());
    }
    @Test void alertInsertFailureRollsBackBothDestinations() {
        var before=MatchState.start("match","home","away");var event=new MatchEvent("g","match",EventType.GOAL,"home",10,Instant.EPOCH,null);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture")).when(jdbc).update(startsWith("INSERT INTO match_state.alert_outbox"),any(Object[].class));
        var store=new JdbcMatchStateStore(jdbc,new TransactionTemplate(manager),mapper,"live_updates","score.{matchId}",true);
        assertThrows(RuntimeException.class,()->store.compareAndSet(new LoadedMatch(before,0),before.apply(event),new ScoreUpdate("match",1,0,"g",EventType.GOAL,Instant.EPOCH,VarStatus.CLEAR,null)));
        verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void defaultStoreDoesNotDependOnAlertOutbox() {
        var before=MatchState.start("match","home","away");var event=new MatchEvent("g","match",EventType.GOAL,"home",10,Instant.EPOCH,null);
        var store=new JdbcMatchStateStore(jdbc,new TransactionTemplate(manager),mapper,"live_updates","score.{matchId}");
        assertTrue(store.compareAndSet(new LoadedMatch(before,0),before.apply(event),new ScoreUpdate("match",1,0,"g",EventType.GOAL,Instant.EPOCH,VarStatus.CLEAR,null)));
        verify(jdbc,never()).update(contains("alert_outbox"),any(Object[].class));
    }
    @Test void publicationFailureRetainsPendingAndRetryUsesIdenticalStoredIdentity() {
        String id=UUID.randomUUID().toString();var rabbit=mock(RabbitTemplate.class);
        when(jdbc.queryForList(startsWith("SELECT o.alert_id"))).thenReturn(List.of(Map.of("alert_id",id,"payload","immutable-payload","exchange_name","live_alerts")));
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{
            if(attempts.getAndIncrement()==0) throw new org.springframework.amqp.AmqpException("fixture");
            CorrelationData data=call.getArgument(3);data.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(eq("live_alerts"),eq(""),any(Message.class),any(CorrelationData.class));
        var dispatcher=new LiveAlertOutboxDispatcher(jdbc,new TransactionTemplate(manager),rabbit,Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class,dispatcher::drainOne);
        verify(jdbc,never()).update(startsWith("UPDATE match_state.alert_outbox"),any(Object[].class));
        verify(manager).rollback(any());
        assertTrue(dispatcher.drainOne());
        var bodies=org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbit,times(2)).send(eq("live_alerts"),eq(""),bodies.capture(),any(CorrelationData.class));
        assertArrayEquals(bodies.getAllValues().get(0).getBody(),bodies.getAllValues().get(1).getBody());
        assertEquals(id,bodies.getAllValues().get(1).getMessageProperties().getMessageId());
        verify(jdbc).update(startsWith("UPDATE match_state.alert_outbox"),eq(id));
        verify(jdbc,never()).update(contains("score_outbox"),any(Object[].class));
    }
    @Test void annulmentPreservesOriginalGoalAndContinuationProducesNoAlert() {
        var alert=LiveAlertV1.create(new ScoreUpdate("m",0,0,"decision",EventType.GOAL_ANNULLED,Instant.EPOCH,VarStatus.CLEAR,"goal")).orElseThrow();
        assertEquals("goal",alert.referenceEventId());
        assertTrue(LiveAlertV1.create(new ScoreUpdate("m",0,0,"continue",EventType.MATCH_CONTINUES,Instant.EPOCH,VarStatus.CLEAR,null)).isEmpty());
    }
    @Test void offDoesNotRegisterTheFanoutOrPublisherScheduler() {
        new ApplicationContextRunner().withUserConfiguration(LiveAlertPublicationConfiguration.class)
            .withPropertyValues("match-state.alerts.publication-enabled=false")
            .run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(FanoutExchange.class).isEmpty());assertTrue(context.getBeansOfType(LiveAlertPublicationConfiguration.class).isEmpty());});
    }
}
