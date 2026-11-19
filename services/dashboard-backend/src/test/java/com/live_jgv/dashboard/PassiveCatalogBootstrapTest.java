package com.live_jgv.dashboard;
import com.live_jgv.dashboard.infrastructure.jdbc.ScoreProjection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.dashboard.application.port.out.MatchSnapshotPublisher;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PassiveCatalogBootstrapTest {
    private static final String SELECT="SELECT * FROM dashboard.matches WHERE match_id=?";
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private Map<String,Object> catalog() {
        return new HashMap<>(Map.of("home_team_id","home","away_team_id","away",
                "home_name","Home","away_name","Away"));
    }
    private ScoreProjection passive() {
        return new ScoreProjection(jdbc,mock(PlatformTransactionManager.class),new ObjectMapper(),
                mock(MatchSnapshotPublisher.class),"match","home","away","Home","Away",false);
    }
    private void onlyCatalogSelect() {
        verify(jdbc).queryForMap(SELECT,"match");
        verifyNoMoreInteractions(jdbc);
    }
    @Test void normalBootstrapStillInsertsThenValidatesTeamsAndRefreshesNames() {
        var row=catalog(); row.put("home_name","Old home"); row.put("away_name","Old away");
        when(jdbc.queryForMap(SELECT,"match")).thenReturn(row);
        new ScoreProjection(jdbc,mock(PlatformTransactionManager.class),new ObjectMapper(),
                mock(MatchSnapshotPublisher.class),"match","home","away","Home","Away");
        var order=inOrder(jdbc);
        order.verify(jdbc).update("INSERT INTO dashboard.matches(match_id,home_team_id,away_team_id,home_name,away_name) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                "match","home","away","Home","Away");
        order.verify(jdbc).queryForMap(SELECT,"match");
        order.verify(jdbc).update("UPDATE dashboard.matches SET home_name=?,away_name=? WHERE match_id=?","Home","Away","match");
        verifyNoMoreInteractions(jdbc);
    }
    @Test void passiveStartupOnlySelectsAnExistingMatchingCatalog() {
        when(jdbc.queryForMap(SELECT,"match")).thenReturn(catalog());
        assertNotNull(passive());
        onlyCatalogSelect();
    }
    @ParameterizedTest @ValueSource(strings={"home_team_id","away_team_id","home_name","away_name"})
    void passiveStartupNeverRepairsInconsistentCatalog(String field) {
        var row=catalog(); row.put(field,"different");
        when(jdbc.queryForMap(SELECT,"match")).thenReturn(row);
        var failure=assertThrows(IllegalStateException.class,this::passive);
        assertFalse(failure.getMessage().isBlank());
        onlyCatalogSelect();
    }
    @Test void missingMatchFailsWithProvisioningDiagnosisWithoutInserting() {
        when(jdbc.queryForMap(SELECT,"match")).thenThrow(new EmptyResultDataAccessException(1));
        var failure=assertThrows(IllegalStateException.class,this::passive);
        assertTrue(failure.getMessage().contains("pre-provisioned catalog"));
        onlyCatalogSelect();
    }
    @Test void unavailableSchemaOrSelectAccessFailsWithoutInitializingAnything() {
        when(jdbc.queryForMap(SELECT,"match")).thenThrow(new DataAccessResourceFailureException("test double"));
        var failure=assertThrows(IllegalStateException.class,this::passive);
        assertTrue(failure.getMessage().contains("schema availability and SELECT permissions"));
        onlyCatalogSelect();
    }
}
