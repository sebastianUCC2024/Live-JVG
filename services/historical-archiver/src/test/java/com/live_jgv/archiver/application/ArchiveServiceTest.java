package com.live_jgv.archiver.application;

import com.live_jgv.archiver.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveServiceTest {
    private final ArchiveStore store=mock(ArchiveStore.class);
    private final EventDecoder decoder=mock(EventDecoder.class);
    private final ArchiveService service=new ArchiveService(store,decoder);
    private final InputRecord record=new InputRecord("s","t",0,0,0,null,null,"[]");
    @Test void invalidInputIsPassedToDurableQuarantine() {
        var invalid=DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON);when(decoder.decode(record)).thenReturn(invalid);
        when(store.save(record,invalid)).thenReturn(ArchiveOutcome.QUARANTINED);
        assertEquals(ArchiveOutcome.QUARANTINED,service.archive(record));verify(store).save(record,invalid);
    }
    @Test void storageFailureEscapesSoOffsetCannotBeAcknowledged() {
        var invalid=DecodedInput.invalid(DecodedInput.Reason.INVALID_EVENT);when(decoder.decode(record)).thenReturn(invalid);
        when(store.save(record,invalid)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThrows(DataAccessResourceFailureException.class,()->service.archive(record));
    }
    @ParameterizedTest @CsvSource({"-1,10","0,0","0,201"}) void rejectsInvalidPagination(int page,int size) {
        assertThrows(IllegalArgumentException.class,()->service.history("m",page,size));verifyNoInteractions(store);
    }
    @Test void delegatesRealPaginationToStore() {
        var expected=new HistoryPage(java.util.List.of(),3,20,0,0);when(store.find("m",3,20)).thenReturn(expected);
        assertSame(expected,service.history("m",3,20));
    }
}
