package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import com.live_jgv.worker.infrastructure.JsonCommandDecoder;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerServiceTest {
    private final SimulatedCommand command=new SimulatedCommand("c","o","m","sim-user",Instant.parse("2026-11-16T00:00:00Z"));
    @Test void domainCommandUsesExistingStoreWithoutDecodingExternalInput(){
        var store=mock(WorkerStore.class);var decoder=mock(CommandDecoder.class);
        when(store.handle(any(),any())).thenReturn(WorkerStore.Outcome.PROCESSED);
        assertEquals(WorkerStore.Outcome.PROCESSED,new WorkerService(store,decoder,new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation()).submit(command));
        var message=ArgumentCaptor.forClass(TransportMessage.class);var decoded=ArgumentCaptor.forClass(DecodedCommand.class);
        verify(store).handle(message.capture(),decoded.capture());verifyNoInteractions(decoder);
        assertEquals(command,decoded.getValue().command());assertNull(message.getValue().messageId());
        assertEquals("internal/domain",message.getValue().sourceId());
        assertEquals(decoded.getValue().canonicalPayload(),new String(message.getValue().body(),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(decoded.getValue(),new JsonCommandDecoder().decode(message.getValue()));
    }
    @Test void domainPreparationIsDeterministicAndEscapesIdentityWithoutChangingIt(){
        var special=new SimulatedCommand("c\"\\\n","o","m","sim-user",command.timestamp());
        var a=new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation().prepare(special);var b=new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation().prepare(special);
        assertEquals(a.decoded(),b.decoded());assertEquals(special,new JsonCommandDecoder().decode(a.message()).command());
        var conflict=new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation().prepare(new SimulatedCommand(special.commandId(),"o","m","other-user",special.timestamp()));
        assertNotEquals(a.decoded().canonicalPayload(),conflict.decoded().canonicalPayload());
    }
    @Test void invalidDomainInputCannotReachStorage(){
        var store=mock(WorkerStore.class);var service=new WorkerService(store,mock(CommandDecoder.class),new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation());
        assertThrows(NullPointerException.class,()->service.submit(null));
        assertThrows(IllegalArgumentException.class,()->service.submit(new SimulatedCommand(" ","o","m","u",command.timestamp())));
        verifyNoInteractions(store);
    }
    @Test void domainPersistenceFailureIsPropagatedAndSameCommandCanBeRetried(){
        var store=mock(WorkerStore.class);var decoder=mock(CommandDecoder.class);
        when(store.handle(any(),any())).thenThrow(new DataAccessResourceFailureException("down")).thenReturn(WorkerStore.Outcome.PROCESSED);
        var service=new WorkerService(store,decoder,new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation());
        assertThrows(DataAccessResourceFailureException.class,()->service.submit(command));
        assertEquals(WorkerStore.Outcome.PROCESSED,service.submit(command));
        var requests=ArgumentCaptor.forClass(DecodedCommand.class);verify(store,times(2)).handle(any(),requests.capture());
        assertEquals(requests.getAllValues().get(0),requests.getAllValues().get(1));verifyNoInteractions(decoder);
    }
    @Test void malformedInputGoesToDurableDisposition(){
        var store=mock(WorkerStore.class);var decoder=mock(CommandDecoder.class);var raw=new TransportMessage("s",null,"x","k",null,"{}");
        var invalid=DecodedCommand.invalid("MALFORMED_JSON");when(decoder.decode(raw)).thenReturn(invalid);when(store.handle(raw,invalid)).thenReturn(WorkerStore.Outcome.QUARANTINED);
        assertEquals(WorkerStore.Outcome.QUARANTINED,new WorkerService(store,decoder,new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation()).receive(raw));
    }
    @Test void persistenceFailurePropagatesToPreventAutoAck(){
        var store=mock(WorkerStore.class);when(store.handle(any(),any())).thenThrow(new DataAccessResourceFailureException("down"));
        assertThrows(DataAccessResourceFailureException.class,()->new WorkerService(store,mock(CommandDecoder.class),new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation()).reject(new TransportMessage("s",null,"x","k",null,"{}"),"INVALID"));
    }
    @Test void blankQueryCannotTouchStorage(){
        var store=mock(WorkerStore.class);assertThrows(IllegalArgumentException.class,()->new WorkerService(store,mock(CommandDecoder.class),new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation()).find(" "));verifyNoInteractions(store);
    }
}
