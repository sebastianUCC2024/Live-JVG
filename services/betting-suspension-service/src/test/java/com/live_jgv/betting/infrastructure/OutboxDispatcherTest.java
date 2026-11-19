package com.live_jgv.betting.infrastructure;

import com.live_jgv.betting.application.SuspensionPublisher;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class OutboxDispatcherTest {
    JdbcTemplate jdbc;PlatformTransactionManager manager;SuspensionPublisher publisher;OutboxDispatcher dispatcher;
    @BeforeEach void setup() {
        jdbc=mock(JdbcTemplate.class);manager=mock(PlatformTransactionManager.class);publisher=mock(SuspensionPublisher.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());dispatcher=new OutboxDispatcher(jdbc,new TransactionTemplate(manager),publisher);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("message_id","stable-id","payload","stored-image")));
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
    }
    @Test void publicationPrecedesDatabaseMarkerAndCommitWithStableImage() {
        assertTrue(dispatcher.drainOne());var order=inOrder(jdbc,publisher,manager);
        order.verify(jdbc).queryForList(contains("SKIP LOCKED"));order.verify(publisher).publish("stable-id","stored-image");
        order.verify(jdbc).update(contains("published_at=CURRENT_TIMESTAMP"),eq("stable-id"));order.verify(manager).commit(any());
        verify(jdbc).queryForList(contains("earlier.version<o.version AND earlier.published_at IS NULL"));
    }
    @Test void uncertainBrokerResultLeavesOutboxPending() {
        doThrow(new IllegalStateException("timeout")).when(publisher).publish(anyString(),anyString());
        assertThrows(IllegalStateException.class,dispatcher::drainOne);verify(jdbc,never()).update(anyString(),any(Object[].class));verify(manager).rollback(any());
    }
    @Test void failedDatabaseMarkerAfterBrokerConfirmationIsRetriableWithSameId() {
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class,dispatcher::drainOne);verify(manager).rollback(any());verify(publisher).publish("stable-id","stored-image");
    }
    @Test void emptyOutboxDoesNotPublish() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());assertFalse(dispatcher.drainOne());verifyNoInteractions(publisher);
    }
}
