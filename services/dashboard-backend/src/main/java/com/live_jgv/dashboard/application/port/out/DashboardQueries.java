package com.live_jgv.dashboard.application.port.out;

import com.live_jgv.dashboard.domain.model.MatchSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read boundary for the existing durable REST catalog, history and recovery views. */
public interface DashboardQueries {
    List<Map<String, Object>> matches();
    Map<String, Object> view(String matchId);
    boolean operationProcessed(String matchId, String eventId);
    Optional<MatchSnapshot> snapshot(String matchId);
}
