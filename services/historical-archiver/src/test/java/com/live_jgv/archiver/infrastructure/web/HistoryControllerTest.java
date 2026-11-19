package com.live_jgv.archiver.infrastructure.web;

import com.live_jgv.archiver.application.*;
import com.live_jgv.archiver.domain.HistoryPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HistoryController.class)
@Import(ArchiveService.class)
class HistoryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ArchiveStore store;
    @MockitoBean EventDecoder decoder;
    @Test void defaultsAndEmptyMatchAreARealEmptyPage() throws Exception {
        when(store.find("m",0,50)).thenReturn(new HistoryPage(List.of(),0,50,0,0));
        mvc.perform(get("/api/history/matches/m/events")).andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.total_elements").value(0))
            .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50));
    }
    @Test void eventResponseUsesSnakeCaseAndDoesNotExposeRawEvidence() throws Exception {
        var time=java.time.Instant.parse("2026-11-15T00:00:00.123456789Z");
        var entry=new HistoryPage.Entry(1,"e","m","GOAL","h",10,time,null,java.time.Instant.EPOCH);
        when(store.find("m",0,50)).thenReturn(new HistoryPage(List.of(entry),0,50,1,1));
        mvc.perform(get("/api/history/matches/m/events")).andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].event_id").value("e"))
            .andExpect(jsonPath("$.content[0].timestamp").value(time.toString()))
            .andExpect(jsonPath("$.content[0].original_payload").doesNotExist());
    }
    @Test void pageAndSizeArePassedToSqlAdapter() throws Exception {
        when(store.find("m",2,10)).thenReturn(new HistoryPage(List.of(),2,10,30,3));
        mvc.perform(get("/api/history/matches/m/events?page=2&size=10")).andExpect(status().isOk())
            .andExpect(jsonPath("$.total_pages").value(3));verify(store).find("m",2,10);
    }
    @ParameterizedTest @ValueSource(strings={"page=-1","size=0","size=201","page=abc","size=1.5","page=2147483648"})
    void invalidParametersReturn400BeforeQueryingStorage(String query) throws Exception {
        mvc.perform(get("/api/history/matches/m/events?"+query)).andExpect(status().isBadRequest());verifyNoInteractions(store);
    }
    @Test void connectionFailureBeforeTransactionReturns503() throws Exception {
        when(store.find("m",0,50)).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("unavailable"));
        mvc.perform(get("/api/history/matches/m/events")).andExpect(status().isServiceUnavailable());
    }
    @Test void storageFailureReturns503WithoutLeakingItsMessage() throws Exception {
        when(store.find("m",0,50)).thenThrow(new DataAccessResourceFailureException("secret-password-host"));
        mvc.perform(get("/api/history/matches/m/events")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("Historical storage is unavailable"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-password-host"))));
    }
}
