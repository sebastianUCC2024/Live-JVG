package com.live_jgv.notification.infrastructure.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.live_jgv.notification.Fixtures;
import com.live_jgv.notification.application.*;
import com.live_jgv.notification.infrastructure.jdbc.AlertCursor;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AlertControllerTest {
    NotificationStore store; MockMvc mvc;
    @BeforeEach void setup() {
        store=mock(NotificationStore.class);
        mvc=MockMvcBuilders.standaloneSetup(new AlertController(store)).setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void returnsServiceLocalSnakeCaseDtoAndOpaqueCursor() throws Exception {
        when(store.query("match-001",null,50)).thenReturn(new NotificationStore.AlertPage(List.of(Fixtures.alert()),"opaque",false));
        mvc.perform(get("/api/alerts").param("match_id","match-001"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.items[0].contract_version").value("1.0"))
            .andExpect(jsonPath("$.items[0].alert_type").value("GOAL"))
            .andExpect(jsonPath("$.items[0].match_id").value("match-001"))
            .andExpect(jsonPath("$.items[0].source_updated_at").value("2026-11-15T15:30:00.123456789Z"))
            .andExpect(jsonPath("$.next_cursor").value("opaque")).andExpect(jsonPath("$.has_more").value(false));
    }
    @Test void passesIncrementalCursorAndLimit() throws Exception {
        when(store.query("match-001","cursor",2)).thenReturn(new NotificationStore.AlertPage(List.of(),"next",false));
        mvc.perform(get("/api/alerts").param("match_id","match-001").param("after","cursor").param("limit","2")).andExpect(status().isOk());
        verify(store).query("match-001","cursor",2);
    }
    @Test void rejectsInvalidMissingAndUnboundedQueries() throws Exception {
        mvc.perform(get("/api/alerts")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alerts").param("match_id"," ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alerts").param("match_id","m").param("limit","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alerts").param("match_id","m").param("limit","bad")).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
    @Test void staleCursorReturnsConflict() throws Exception {
        when(store.query(anyString(),any(),anyInt())).thenThrow(new AlertCursor.StaleCursorException());
        mvc.perform(get("/api/alerts").param("match_id","m").param("after","old")).andExpect(status().isConflict());
    }
    @Test void disabledOrFailedStorageReturns503WithoutSecrets() throws Exception {
        when(store.query(anyString(),any(),anyInt())).thenThrow(new StorageUnavailableException())
            .thenThrow(new DataAccessResourceFailureException("secret-db-password"));
        for (int i=0;i<2;i++) mvc.perform(get("/api/alerts").param("match_id","m")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Las notificaciones no están disponibles temporalmente."));
    }
}
