package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerServiceTest {
    @Test void malformedInputGoesToDurableDisposition(){
        var store=mock(WorkerStore.class);var decoder=mock(CommandDecoder.class);var raw=new TransportMessage("s",null,"x","k",null,"{}");
        var invalid=DecodedCommand.invalid("MALFORMED_JSON");when(decoder.decode(raw)).thenReturn(invalid);when(store.handle(raw,invalid)).thenReturn(WorkerStore.Outcome.QUARANTINED);
        assertEquals(WorkerStore.Outcome.QUARANTINED,new WorkerService(store,decoder).receive(raw));
    }
    @Test void persistenceFailurePropagatesToPreventAutoAck(){
        var store=mock(WorkerStore.class);when(store.handle(any(),any())).thenThrow(new DataAccessResourceFailureException("down"));
        assertThrows(DataAccessResourceFailureException.class,()->new WorkerService(store,mock(CommandDecoder.class)).reject(new TransportMessage("s",null,"x","k",null,"{}"),"INVALID"));
    }
    @Test void blankQueryCannotTouchStorage(){
        var store=mock(WorkerStore.class);assertThrows(IllegalArgumentException.class,()->new WorkerService(store,mock(CommandDecoder.class)).find(" "));verifyNoInteractions(store);
    }
}
