package com.live_jgv.dashboard;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
@org.springframework.context.annotation.Profile("!memory")
@RestController
@RequestMapping("/api")
public class DashboardController{
 private final ScoreProjection scores;private final ProducerGateway producer;
 public DashboardController(ScoreProjection scores,ProducerGateway producer){this.scores=scores;this.producer=producer;}
 @GetMapping("/matches") public List<Map<String,Object>> matches(){return scores.matches();}
 @GetMapping("/matches/{id}") public Map<String,Object> match(@PathVariable String id){return scores.view(id);}
 @PostMapping("/matches/{id}/events") public ResponseEntity<?> event(@PathVariable String id,@RequestBody Map<String,Object> body){
  var match=scores.view(id);String type=String.valueOf(body.get("event_type"));
  if(!Set.of("GOAL","VAR_CHECK","GOAL_ANNULLED","MATCH_CONTINUES").contains(type))throw new IllegalArgumentException("Tipo de evento inválido");
  if(type.equals("GOAL")){
   var team=body.get("team_id");if(!Objects.equals(team,match.get("home_team_id"))&&!Objects.equals(team,match.get("away_team_id")))throw new IllegalArgumentException("Selecciona un equipo del partido");
  }
  if(type.equals("GOAL_ANNULLED")||(type.equals("VAR_CHECK")&&body.get("reference_event_id")!=null)){
   @SuppressWarnings("unchecked")var goals=(List<com.fasterxml.jackson.databind.JsonNode>)match.get("active_goals");
   if(goals.stream().noneMatch(g->g.path("event_id").asText().equals(body.get("reference_event_id"))))throw new IllegalArgumentException("Selecciona un gol activo para revisar");
  }
  return ResponseEntity.accepted().body(producer.publish(id,body));
 }
 @ExceptionHandler(NoSuchElementException.class)ResponseEntity<?> missing(NoSuchElementException e){return ResponseEntity.status(404).body(Map.of("error",e.getMessage()));}
 @ExceptionHandler(IllegalArgumentException.class)ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
}
