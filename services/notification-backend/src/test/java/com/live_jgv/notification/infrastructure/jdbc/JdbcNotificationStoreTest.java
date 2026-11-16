package com.live_jgv.notification.infrastructure.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.live_jgv.notification.Fixtures;
import com.live_jgv.notification.application.NotificationStore;
import com.live_jgv.notification.domain.*;
import com.live_jgv.notification.infrastructure.json.ScoreUpdateCodec;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

class JdbcNotificationStoreTest {
    JdbcTemplate jdbc; PlatformTransactionManager manager; JdbcNotificationStore store;
    final ScoreUpdateCodec codec=new ScoreUpdateCodec();
    final LiveAlert alert=Fixtures.alert();
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class); manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(ignored->new SimpleTransactionStatus());
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(startsWith("UPDATE notification.cursor_state"),eq(Long.class))).thenReturn(1L);
        store=new JdbcNotificationStore(jdbc,manager,codec);
    }
    @Test void inboxAndAlertCommitTogetherWithSerializedSequence() {
        assertEquals(NotificationStore.Result.CREATED,store.accept(alert.source(),Optional.of(alert)));
        var order=inOrder(manager,jdbc);
        order.verify(manager).getTransaction(any());
        order.verify(jdbc).update(startsWith("INSERT INTO notification.inbox"),any(Object[].class));
        order.verify(jdbc).queryForObject(startsWith("UPDATE notification.cursor_state"),eq(Long.class));
        order.verify(jdbc).update(startsWith("INSERT INTO notification.alerts"),any(Object[].class));
        order.verify(manager).commit(any()); verify(manager,never()).rollback(any());
        var definition=org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getValue().getPropagationBehavior());
    }
    @Test void actualRepositoryCommitsBeforeListenerAcknowledges() throws Exception {
        var channel=mock(com.rabbitmq.client.Channel.class);
        var template=mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        var processor=new com.live_jgv.notification.application.ProcessScoreUpdateService(store,new NotificationPolicy(),java.time.Clock.systemUTC());
        var listener=new com.live_jgv.notification.infrastructure.amqp.ScoreUpdateListener(processor,codec,
                new com.live_jgv.notification.infrastructure.amqp.ConfirmedDlqRecovery(template,"notification.dlx",java.time.Duration.ofSeconds(1)),
                ()->{},millis->{},3,1);
        var properties=new org.springframework.amqp.core.MessageProperties(); properties.setDeliveryTag(7);
        listener.receive(new org.springframework.amqp.core.Message(Fixtures.JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8),properties),channel);
        var order=inOrder(manager,channel); order.verify(manager).commit(any()); order.verify(channel).basicAck(7,false);
    }
    @Test void alertFailureRollsBackInboxAndCursor() {
        doThrow(new DataAccessResourceFailureException("simulated")).when(jdbc).update(startsWith("INSERT INTO notification.alerts"),any(Object[].class));
        assertThrows(DataAccessResourceFailureException.class,()->store.accept(alert.source(),Optional.of(alert)));
        verify(manager).rollback(any()); verify(manager,never()).commit(any());
    }
    @Test void redeliveryAfterRepositoryRestartDoesNotCreateAnotherAlert() {
        when(jdbc.update(startsWith("INSERT INTO notification.inbox"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(startsWith("SELECT payload"),eq(String.class),any(Object[].class))).thenReturn(codec.encode(alert.source()));
        var restarted=new JdbcNotificationStore(jdbc,manager,codec);
        assertEquals(NotificationStore.Result.DUPLICATE,restarted.accept(alert.source(),Optional.of(Fixtures.alert())));
        verify(jdbc,never()).update(startsWith("INSERT INTO notification.alerts"),any(Object[].class));
        verify(manager).commit(any());
    }
    @Test void reusedIdentityWithDifferentContentIsConflict() {
        when(jdbc.update(startsWith("INSERT INTO notification.inbox"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(startsWith("SELECT payload"),eq(String.class),any(Object[].class)))
                .thenReturn(codec.encode(Fixtures.update(ScoreUpdate.EventType.VAR_CHECK)));
        assertThrows(IdentityConflictException.class,()->store.accept(alert.source(),Optional.of(alert)));
        verify(manager).rollback(any());
    }
    @Test void continuesPersistsOnlyInbox() {
        assertEquals(NotificationStore.Result.SUPPRESSED,store.accept(Fixtures.update(ScoreUpdate.EventType.MATCH_CONTINUES),Optional.empty()));
        verify(jdbc,never()).queryForObject(startsWith("UPDATE notification.cursor_state"),eq(Long.class));
        verify(jdbc,never()).update(startsWith("INSERT INTO notification.alerts"),any(Object[].class));
        verify(manager).commit(any());
    }
    @SuppressWarnings("unchecked")
    @Test void initialPageIsLatestWindowInAscendingOrderAndReturnsHighWater() {
        UUID epoch=UUID.randomUUID();
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("epoch",epoch,"last_sequence",8L));
        var older=new NotificationPolicy().create(Fixtures.update(ScoreUpdate.EventType.VAR_CHECK),java.time.Instant.EPOCH).orElseThrow();
        doReturn(List.of(new JdbcNotificationStore.StoredAlert(7,alert),new JdbcNotificationStore.StoredAlert(5,older)))
                .when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        var page=store.query("match-001",null,2);
        assertEquals(2,page.items().size()); assertFalse(page.hasMore());
        assertEquals(List.of(older,alert),page.items());
        assertEquals(8,AlertCursor.decode(page.nextCursor(),"match-001").sequence());
    }
    @SuppressWarnings("unchecked")
    @Test void incrementalPagesAdvanceOnlyThroughReturnedRowsWhenMoreExist() {
        UUID epoch=UUID.randomUUID();
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("epoch",epoch,"last_sequence",10L));
        doReturn(List.of(new JdbcNotificationStore.StoredAlert(3,alert),new JdbcNotificationStore.StoredAlert(5,alert),new JdbcNotificationStore.StoredAlert(8,alert)))
                .when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        var page=store.query("match-001",new AlertCursor(epoch,"match-001",1).encode(),2);
        assertTrue(page.hasMore()); assertEquals(5,AlertCursor.decode(page.nextCursor(),"match-001").sequence());
    }
    @Test void detectsStaleEpochAndCursorAheadOfRestoredDatabase() {
        UUID epoch=UUID.randomUUID();
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("epoch",epoch,"last_sequence",2L));
        assertThrows(AlertCursor.StaleCursorException.class,()->store.query("match-001",new AlertCursor(UUID.randomUUID(),"match-001",1).encode(),50));
        assertThrows(AlertCursor.StaleCursorException.class,()->store.query("match-001",new AlertCursor(epoch,"match-001",3).encode(),50));
    }
    @Test void cursorIsScopedVersionedAndLosslessAboveJavascriptIntegerLimit() {
        var cursor=new AlertCursor(UUID.randomUUID(),"match-001",9007199254740993L);
        assertEquals(cursor,AlertCursor.decode(cursor.encode(),"match-001"));
        assertThrows(IllegalArgumentException.class,()->AlertCursor.decode(cursor.encode(),"match-002"));
        assertThrows(IllegalArgumentException.class,()->AlertCursor.decode("bad","match-001"));
        assertThrows(IllegalArgumentException.class,()->store.query("m",null,101));
    }
}
