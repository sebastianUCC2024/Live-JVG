package com.live_jgv.matchstate.infrastructure.jdbc;

import com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent;
import com.live_jgv.matchstate.domain.model.*;
import com.live_jgv.matchstate.infrastructure.config.MessagingConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real custody classifier with simulated JDBC transport, no database connection. */
class DurableReferencePolicyTest {
    @Test void incompatibleNewReferenceIsQuarantinedInsteadOfEnteringAnInfrastructureRetry() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var mapper = new MessagingConfiguration().messagingObjectMapper();
        var store = mock(JdbcMatchStateStore.class);
        var lane = mock(ProcessAndPublishMatchEvent.class);
        var event = new MatchEvent("bad", "m1", EventType.GOAL, "home", 1, Instant.EPOCH, " ");
        String payload = mapper.writeValueAsString(event);
        when(store.load("m1")).thenReturn(Optional.of(new LoadedMatch(MatchState.start("m1", "home", "away"), 0)));
        when(jdbc.queryForMap(contains("FROM match_state.event_identity"), eq("m1"), eq("bad")))
                .thenReturn(Map.of("canonical_payload", payload, "status", "NEW"));
        var ingress = new DurableEventIngress(jdbc, new TransactionTemplate(manager), mapper, store, lane);
        assertDoesNotThrow(() -> ingress.handle(new InputRecord("source", "events", 0, 0, 0,
                "m1".getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8), "[]")));
        verify(jdbc).update(startsWith("INSERT INTO match_state.quarantine_case"), any(UUID.class),
                eq("DOMAIN_INVALID"), eq("QUARANTINED"), eq("m1"), eq("bad"), eq(" "), eq(payload));
        verify(store, never()).compareAndSet(any(), any(), any());
        verifyNoInteractions(lane);
        verify(manager).commit(any());
    }
}
