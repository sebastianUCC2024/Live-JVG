package com.live_jgv.notification.infrastructure.jdbc;
import com.live_jgv.notification.*;
import com.live_jgv.notification.application.*;
import com.live_jgv.notification.domain.*;
import com.live_jgv.notification.infrastructure.json.*;
import com.live_jgv.notification.infrastructure.amqp.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.rabbitmq.client.Channel;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class LiveAlertPersistenceTest {
    JdbcTemplate jdbc;PlatformTransactionManager manager;JdbcNotificationStore store;
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class);manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(x->new SimpleTransactionStatus());
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(startsWith("UPDATE notification.cursor_state"),eq(Long.class))).thenReturn(1L);
        store=new JdbcNotificationStore(jdbc,manager,new ScoreUpdateCodec());
    }
    @Test void liveAlertInboxAndRestAlertCommitBeforeAck() throws Exception {
        var wire=LiveAlertContractTest.wire();var channel=mock(Channel.class);
        var listener=new ScoreUpdateListener(new ProcessScoreUpdateService(store,new NotificationPolicy(),Clock.systemUTC()),new ScoreUpdateCodec(),
            new ConfirmedDlqRecovery(mock(RabbitTemplate.class),"notification.fixture.dlx",Duration.ofSeconds(1)),()->{},x->{},3,1).withLiveAlerts(Set.of("match"));
        var props=new MessageProperties();props.setMessageId(wire.alertId().toString());props.setReceivedExchange("live_alerts");props.setDeliveryTag(7);
        listener.receive(new Message(new LiveAlertCodec().encode(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8),props),channel);
        var order=inOrder(jdbc,manager,channel);
        order.verify(jdbc).update(startsWith("INSERT INTO notification.live_alert_inbox"),any(Object[].class));
        order.verify(jdbc).update(startsWith("INSERT INTO notification.inbox"),any(Object[].class));
        order.verify(jdbc).update(startsWith("INSERT INTO notification.alerts"),any(Object[].class));
        order.verify(manager).commit(any());order.verify(channel).basicAck(7,false);
    }
    @Test void identicalRedeliveryDoesNotInsertAnotherAlert() {
        var wire=LiveAlertContractTest.wire();
        when(jdbc.update(startsWith("INSERT INTO notification.live_alert_inbox"),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForList(startsWith("SELECT payload::text"),any(Object[].class))).thenReturn(List.of(Map.of("payload",new LiveAlertCodec().encode(wire))));
        assertEquals(NotificationStore.Result.DUPLICATE,store.acceptLiveAlert(wire));
        verify(jdbc,never()).update(startsWith("INSERT INTO notification.alerts"),any(Object[].class));
    }
    @Test void conflictingIdentityRollsBack() {
        var wire=LiveAlertContractTest.wire();
        when(jdbc.update(startsWith("INSERT INTO notification.live_alert_inbox"),any(Object[].class))).thenReturn(0);
        String previous=new LiveAlertCodec().encode(wire).replace("Gol registrado.","Different message.");
        when(jdbc.queryForList(startsWith("SELECT payload::text"),any(Object[].class))).thenReturn(List.of(Map.of("payload",previous)));
        assertThrows(IdentityConflictException.class,()->store.acceptLiveAlert(wire));verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void alertInsertFailureRollsBackWireIdentityAndCoreInbox() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture")).when(jdbc).update(startsWith("INSERT INTO notification.alerts"),any(Object[].class));
        assertThrows(RuntimeException.class,()->store.acceptLiveAlert(LiveAlertContractTest.wire()));verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
}
