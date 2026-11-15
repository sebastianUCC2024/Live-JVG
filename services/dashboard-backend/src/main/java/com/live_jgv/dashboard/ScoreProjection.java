package com.live_jgv.dashboard;

import com.fasterxml.jackson.databind.*;
import com.live_jgv.dashboard.domain.model.MatchSnapshot;
import com.live_jgv.dashboard.domain.port.out.MatchSnapshotPublisher;
import com.live_jgv.dashboard.infrastructure.amqp.ScoreUpdateMessageDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import java.util.*;

@org.springframework.context.annotation.Profile("!memory")
@Repository
@DependsOnDatabaseInitialization
public class ScoreProjection {
    private final MatchSnapshotPublisher publisher;
    private final ScoreUpdateMessageDecoder decoder = new ScoreUpdateMessageDecoder();
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final ObjectMapper mapper;
    public ScoreProjection(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper mapper,
            MatchSnapshotPublisher publisher,
            @Value("${live.match-id}") String match,@Value("${live.home-team-id}") String home,@Value("${live.away-team-id}") String away,
            @Value("${live.home-name}") String homeName,@Value("${live.away-name}") String awayName){
        this.publisher=publisher;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.mapper=mapper;
        jdbc.update("INSERT INTO dashboard.matches(match_id,home_team_id,away_team_id,home_name,away_name) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",match,home,away,homeName,awayName);
        var row=jdbc.queryForMap("SELECT * FROM dashboard.matches WHERE match_id=?",match);
        if(!home.equals(row.get("home_team_id"))||!away.equals(row.get("away_team_id"))) throw new IllegalStateException("El catálogo persistido no coincide con los equipos configurados");
        jdbc.update("UPDATE dashboard.matches SET home_name=?,away_name=? WHERE match_id=?",homeName,awayName,match);
    }
    @RabbitListener(id="durableScores",queues="${live.queue}",containerFactory="durableScoresFactory")
    public synchronized void receive(Message message){
        decoder.decode(message.getBody());
        final JsonNode json;
        try{json=mapper.readTree(message.getBody());}catch(Exception e){throw new IllegalArgumentException("ScoreUpdate no válido",e);}
        for(String field:List.of("match_id","event_id","event_type","home_score","away_score","var_status","updated_at"))
            if(!json.hasNonNull(field))throw new IllegalArgumentException("Falta campo ScoreUpdate: "+field);
        if(!json.get("home_score").isIntegralNumber()||!json.get("away_score").isIntegralNumber()||json.get("home_score").asInt()<0||json.get("away_score").asInt()<0)throw new IllegalArgumentException("Marcador inválido");
        String match=json.get("match_id").asText(),event=json.get("event_id").asText(),payload=json.toString();
        tx.executeWithoutResult(status->{
            jdbc.queryForMap("SELECT match_id FROM dashboard.matches WHERE match_id=? FOR UPDATE",match);
            var prior=jdbc.queryForList("SELECT payload FROM dashboard.inbox WHERE match_id=? AND event_id=?",match,event);
            if(!prior.isEmpty()){
                try{if(!mapper.readTree((String)prior.get(0).get("payload")).equals(json))throw new com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException("ID de salida con contenido distinto");}
                catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}return;
            }
            jdbc.update("INSERT INTO dashboard.inbox(match_id,event_id,payload) VALUES (?,?,?)",match,event,payload);
            jdbc.update("UPDATE dashboard.matches SET home_score=?,away_score=?,var_status=?,last_event_id=?,updated_at=? WHERE match_id=?",json.get("home_score").asInt(),json.get("away_score").asInt(),json.get("var_status").asText(),event,json.get("updated_at").asText(),match);
        });
        // Publish only committed state. A redelivery publishes the latest snapshot,
        // never the older duplicate payload. Clients deduplicate its durable revision.
        snapshot(match).ifPresent(publisher::publish);
    }
    public synchronized Optional<MatchSnapshot> snapshot(String match) {
        return jdbc.query("""
                SELECT e.epoch,i.seq,i.payload FROM dashboard.stream_epoch e
                  LEFT JOIN LATERAL (SELECT seq,payload FROM dashboard.inbox
                     WHERE match_id=? ORDER BY seq DESC LIMIT 1) i ON TRUE
                 WHERE e.singleton=TRUE
                """, (org.springframework.jdbc.core.ResultSetExtractor<Optional<MatchSnapshot>>) rs -> {
            if (!rs.next()) throw new IllegalStateException("Dashboard stream epoch missing");
            var epoch = java.util.UUID.fromString(rs.getString("epoch"));
            if (rs.getString("payload") == null) return Optional.empty();
            return Optional.of(new MatchSnapshot("durable-"+epoch+"-"+match,rs.getLong("seq"),
                    decoder.decode(rs.getString("payload").getBytes(StandardCharsets.UTF_8))));
        },match);
    }
    public List<Map<String,Object>> matches(){return jdbc.queryForList("SELECT * FROM dashboard.matches ORDER BY match_id");}
    /** Confirmation by identity does not depend on the UI's last-30-events window. */
    public boolean operationProcessed(String matchId,String eventId){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM dashboard.inbox WHERE match_id=? AND event_id=?)",Boolean.class,matchId,eventId));
    }
    public Map<String,Object> view(String id){
        // One PostgreSQL statement uses one MVCC snapshot, including at READ COMMITTED.
        // A REQUIRED/read-only annotation alone would not make two statements consistent.
        return jdbc.query("""
                SELECT row_to_json(m)::text AS match_data,
                       COALESCE((SELECT json_agg(i.payload ORDER BY i.seq)::text
                                   FROM (SELECT seq,payload FROM dashboard.inbox
                                          WHERE match_id=m.match_id ORDER BY seq DESC LIMIT 30) i),'[]') AS history_data,
                       COALESCE((SELECT json_agg(g.payload ORDER BY g.seq)::text
                                   FROM dashboard.inbox g
                                  WHERE g.match_id=m.match_id AND g.payload::jsonb->>'event_type'='GOAL'
                                    AND NOT EXISTS (SELECT 1 FROM dashboard.inbox a
                                         WHERE a.match_id=g.match_id AND a.seq>g.seq
                                           AND a.payload::jsonb->>'event_type'='GOAL_ANNULLED'
                                           AND a.payload::jsonb->>'reference_event_id'=g.event_id)), '[]') AS goals_data
                  FROM dashboard.matches m WHERE m.match_id=?
                """, (org.springframework.jdbc.core.ResultSetExtractor<Map<String,Object>>) rs->{
            if(!rs.next())throw new NoSuchElementException("Partido no encontrado");
            try {
                Map<String,Object> result=mapper.readValue(rs.getString("match_data"),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
                var recent=payloads(rs.getString("history_data"));
                Collections.reverse(recent);
                result.put("events",recent);
                result.put("active_goals",payloads(rs.getString("goals_data")));
                return result;
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid stored dashboard data",e);}
        },id);
    }
    private ArrayList<JsonNode> payloads(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        var result = new ArrayList<JsonNode>();
        for (var payload : mapper.readTree(json)) result.add(mapper.readTree(payload.asText()));
        return result;
    }
}
