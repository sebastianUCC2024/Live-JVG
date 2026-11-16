package com.live_jgv.live_feed_producer.application.service;
import com.live_jgv.live_feed_producer.domain.model.*;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MatchSimulationServiceTest{
 @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "\t\n"})
 void rejectsInvalidConfiguredMatchId(String matchId){
  var publisher=mock(MatchEventPublisher.class);
  assertThrows(IllegalArgumentException.class,()->new MatchSimulationService(publisher,matchId,"h","a"));
  verifyNoInteractions(publisher);
 }
 @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "\t\n"})
 void rejectsInvalidConfiguredHomeTeamId(String home){
  var publisher=mock(MatchEventPublisher.class);
  assertThrows(IllegalArgumentException.class,()->new MatchSimulationService(publisher,"m",home,"a"));
  verifyNoInteractions(publisher);
 }
 @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "\t\n"})
 void rejectsInvalidConfiguredAwayTeamId(String away){
  var publisher=mock(MatchEventPublisher.class);
  assertThrows(IllegalArgumentException.class,()->new MatchSimulationService(publisher,"m","h",away));
  verifyNoInteractions(publisher);
 }
 @Test void rejectsIdenticalConfiguredTeams(){
  var publisher=mock(MatchEventPublisher.class);
  assertThrows(IllegalArgumentException.class,()->new MatchSimulationService(publisher,"m","same","same"));
  verifyNoInteractions(publisher);
 }
 @Test void validConfigurationPublishesForEitherConfiguredTeam(){
  var publisher=mock(MatchEventPublisher.class);
  when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
  var service=assertDoesNotThrow(()->new MatchSimulationService(publisher,"m","h","a"));
  verifyNoInteractions(publisher);
  for(var team:new String[]{"h","a"}){
   var expected=new MatchEvent("goal-"+team,"m",EventType.GOAL,team,1,Instant.EPOCH,null);
   assertEquals(expected,service.publish("m",expected.eventId(),EventType.GOAL,team,1,Instant.EPOCH,null).join());
   verify(publisher).publish(expected);
  }
  verifyNoMoreInteractions(publisher);
 }
 @ParameterizedTest @EnumSource(EventType.class)
 void rejectsBlankReferencesBeforePublication(EventType type){
  var publisher=mock(MatchEventPublisher.class);var service=new MatchSimulationService(publisher,"m","h","a");
  for(var reference:new String[]{""," ","\t\n"})
   assertThrows(IllegalArgumentException.class,()->service.publish("m","id",type,"h",1,Instant.EPOCH,reference));
  verifyNoInteractions(publisher);
 }
 @ParameterizedTest @EnumSource(EventType.class)
 void preservesNonblankReferences(EventType type){
  var publisher=mock(MatchEventPublisher.class);when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
  var result=new MatchSimulationService(publisher,"m","h","a").publish("m","id",type,"h",1,Instant.EPOCH,"original").join();
  assertEquals("original",result.referenceEventId());assertEquals("id",result.eventId());assertEquals(Instant.EPOCH,result.timestamp());
 }
 @Test void annulmentStillRequiresAReference(){
  var publisher=mock(MatchEventPublisher.class);var service=new MatchSimulationService(publisher,"m","h","a");
  assertThrows(IllegalArgumentException.class,()->service.publish("m","id",EventType.GOAL_ANNULLED,null,1,Instant.EPOCH,null));
  verifyNoInteractions(publisher);
 }
 @Test void usesStableClientIdentityAndWaitsForBroker(){var publisher=mock(MatchEventPublisher.class);var ack=new CompletableFuture<Void>();when(publisher.publish(any())).thenReturn(ack);var service=new MatchSimulationService(publisher,"m","h","a");var result=service.publish("m","id",EventType.GOAL,"h",1,Instant.EPOCH,null);assertFalse(result.isDone());ack.complete(null);assertEquals("id",result.join().eventId());verify(publisher).publish(new MatchEvent("id","m",EventType.GOAL,"h",1,Instant.EPOCH,null));}
 @Test void rejectsUnknownMatchOrTeam(){var p=mock(MatchEventPublisher.class);var s=new MatchSimulationService(p,"m","h","a");assertThrows(IllegalArgumentException.class,()->s.publish("other",null,EventType.GOAL,"h",1,null,null));assertThrows(IllegalArgumentException.class,()->s.publish("m",null,EventType.GOAL,"alien",1,null,null));verifyNoInteractions(p);}
}
