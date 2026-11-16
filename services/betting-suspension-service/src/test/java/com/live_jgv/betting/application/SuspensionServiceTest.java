package com.live_jgv.betting.application;

import com.live_jgv.betting.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SuspensionServiceTest {
    @Test void invalidInputGoesToDurableTreatment() {
        var store=mock(SuspensionStore.class);var decoder=mock(EventDecoder.class);
        var record=new InputRecord("s","t",0,0,0,null,null,"[]");var invalid=DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON);
        when(decoder.decode(record)).thenReturn(invalid);when(store.handle(record,invalid)).thenReturn(SuspensionStore.Outcome.QUARANTINED);
        assertEquals(SuspensionStore.Outcome.QUARANTINED,new SuspensionService(store,decoder).receive(record));
    }
    @Test void storageErrorsEscapeWithoutAcknowledge() {
        var store=mock(SuspensionStore.class);var decoder=mock(EventDecoder.class);
        when(store.handle(any(),any())).thenThrow(new DataAccessResourceFailureException("down"));
        assertThrows(DataAccessResourceFailureException.class,()->new SuspensionService(store,decoder).receive(new InputRecord("s","t",0,0,0,null,null,"[]")));
    }
    @Test void blankMatchIsRejectedBeforeReadingStorage() {
        var store=mock(SuspensionStore.class);var service=new SuspensionService(store,mock(EventDecoder.class));
        assertThrows(IllegalArgumentException.class,()->service.status(" "));verifyNoInteractions(store);
    }
}
