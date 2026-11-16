package com.live_jgv.notification.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.live_jgv.notification.Fixtures;
import com.live_jgv.notification.application.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NotificationPolicyTest {
    @ParameterizedTest @EnumSource(value=ScoreUpdate.EventType.class,names={"GOAL","VAR_CHECK","GOAL_ANNULLED"})
    void supportedEventsCreateAlertsWithoutInventingTeams(ScoreUpdate.EventType type) {
        var update=Fixtures.update(type);
        var alert=new NotificationPolicy().create(update,Instant.EPOCH).orElseThrow();
        assertEquals(update,alert.source()); assertEquals(Instant.EPOCH,alert.createdAt());
        assertEquals("es",alert.content().locale()); assertTrue(alert.content().message().contains("1–0"));
        assertNotNull(alert.alertId());
    }
    @Test void continuesIsRecordedWithoutVisibleAlert() {
        var store=mock(NotificationStore.class);
        var service=new ProcessScoreUpdateService(store,new NotificationPolicy(),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        var update=Fixtures.update(ScoreUpdate.EventType.MATCH_CONTINUES);
        service.process(update);
        verify(store).accept(update,java.util.Optional.empty());
    }
    @Test void annulmentRequiresReference() {
        assertThrows(InvalidUpdateException.class,()->new ScoreUpdate("m","e",ScoreUpdate.EventType.GOAL_ANNULLED,0,0,ScoreUpdate.VarStatus.CLEAR,Instant.EPOCH,null));
    }
}
