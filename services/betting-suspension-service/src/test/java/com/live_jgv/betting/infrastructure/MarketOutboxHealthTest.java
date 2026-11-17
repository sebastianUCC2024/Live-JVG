package com.live_jgv.betting.infrastructure;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MarketOutboxHealthTest {
    @Test void reportsBacklogWithoutClaimingDeliveryOrBrokerAvailability() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("pending",4L,"oldest_age_seconds",12.5));
        var health=new MarketOutboxHealth(jdbc,false).health();
        assertEquals(Status.UP,health.getStatus());
        assertEquals(4L,health.getDetails().get("pending"));
        assertEquals(12.5,health.getDetails().get("oldest_age_seconds"));
        assertEquals(false,health.getDetails().get("publication_enabled"));
        assertEquals(false,health.getDetails().get("broker_verified"));
        verify(jdbc).queryForMap(anyString());verifyNoMoreInteractions(jdbc);
    }
    @Test void emptyOutboxIsObservable() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("pending",0L,"oldest_age_seconds",0));
        assertEquals(Status.UP,new MarketOutboxHealth(jdbc,true).health().getStatus());
    }
    @Test void unavailableDatabaseDoesNotExposeExceptionOrClaimReady() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenThrow(new IllegalStateException("private connection detail"));
        var health=new MarketOutboxHealth(jdbc,true).health();
        assertEquals(Status.DOWN,health.getStatus());
        assertEquals(Map.of("state","BACKLOG_UNAVAILABLE"),health.getDetails());
    }
}
