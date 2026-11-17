package com.live_jgv.betting.academic;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class AcademicWireContractTest {
 @Test void goldenProducerWireIsCompatibleWithWorker() throws Exception {var json=new com.fasterxml.jackson.databind.ObjectMapper();
 try(var stream=getClass().getResourceAsStream("/contracts/academic-v1.json")){assertNotNull(stream);var fixture=json.readTree(stream);var mapper=new AcademicCommandMapper();
  for(var node:fixture){String id=node.get("source_event_id").asText();var type=id.equals("goal")?com.live_jgv.betting.domain.SportEvent.EventType.GOAL:com.live_jgv.betting.domain.SportEvent.EventType.GOAL_ANNULLED;
   var event=new com.live_jgv.betting.domain.SportEvent(id,"match",type,"home",10,java.time.Instant.EPOCH,id.equals("annul")?"goal":null);
   assertEquals(node,json.readTree(mapper.map(event).json()));assertEquals(mapper.map(event),mapper.map(event));}
  var a=new com.live_jgv.betting.domain.SportEvent("goal","different",com.live_jgv.betting.domain.SportEvent.EventType.GOAL,"home",10,java.time.Instant.EPOCH,null);
  assertNotEquals(fixture.get(0).get("command_id").asText(),mapper.map(a).commandId());}
 }
}
