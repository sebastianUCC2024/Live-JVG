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
    @RabbitListener(queues="${live.queue}")
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
                try{if(!mapper.readTree((String)prior.get(0).get("payload")).equals(json))throw new IllegalStateException("ID de salida con contenido distinto");}
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
        var rows=jdbc.query("SELECT seq,payload FROM dashboard.inbox WHERE match_id=? ORDER BY seq DESC LIMIT 1",
            (rs,n)->new MatchSnapshot("durable-"+match,rs.getLong(1),
                decoder.decode(rs.getString(2).getBytes(StandardCharsets.UTF_8))),match);
        return rows.stream().findFirst();
    }
    public List<Map<String,Object>> matches(){return jdbc.queryForList("SELECT * FROM dashboard.matches ORDER BY match_id");}
    public Map<String,Object> view(String id){
        var rows=jdbc.queryForList("SELECT * FROM dashboard.matches WHERE match_id=?",id);
        if(rows.isEmpty())throw new NoSuchElementException("Partido no encontrado");
        var result=new LinkedHashMap<String,Object>(rows.get(0));
        var history=jdbc.query("SELECT payload FROM dashboard.inbox WHERE match_id=? ORDER BY seq",(rs,n)->{try{return mapper.readTree(rs.getString(1));}catch(Exception e){throw new IllegalStateException(e);}},id);
        var goals=new LinkedHashMap<String,JsonNode>();
        for(var event:history){String type=event.get("event_type").asText();if(type.equals("GOAL"))goals.put(event.get("event_id").asText(),event);if(type.equals("GOAL_ANNULLED"))goals.remove(event.path("reference_event_id").asText());}
        result.put("active_goals",List.copyOf(goals.values()));
        var recent=new ArrayList<>(history.subList(Math.max(0,history.size()-30),history.size()));Collections.reverse(recent);result.put("events",recent);
        return result;
    }
}
