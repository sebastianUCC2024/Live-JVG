package com.live_jgv.live_feed_producer.application.service;
import com.live_jgv.live_feed_producer.domain.model.*;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MatchSimulationServiceTest{
 @Test void usesStableClientIdentityAndWaitsForBroker(){var publisher=mock(MatchEventPublisher.class);var ack=new CompletableFuture<Void>();when(publisher.publish(any())).thenReturn(ack);var service=new MatchSimulationService(publisher,"m","h","a");var result=service.publish("m","id",EventType.GOAL,"h",1,Instant.EPOCH,null);assertFalse(result.isDone());ack.complete(null);assertEquals("id",result.join().eventId());verify(publisher).publish(new MatchEvent("id","m",EventType.GOAL,"h",1,Instant.EPOCH,null));}
 @Test void rejectsUnknownMatchOrTeam(){var p=mock(MatchEventPublisher.class);var s=new MatchSimulationService(p,"m","h","a");assertThrows(IllegalArgumentException.class,()->s.publish("other",null,EventType.GOAL,"h",1,null,null));assertThrows(IllegalArgumentException.class,()->s.publish("m",null,EventType.GOAL,"alien",1,null,null));verifyNoInteractions(p);}
}
