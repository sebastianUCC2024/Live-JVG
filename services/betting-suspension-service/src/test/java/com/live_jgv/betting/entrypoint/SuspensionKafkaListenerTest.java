package com.live_jgv.betting.entrypoint;

import com.live_jgv.betting.application.*;
import com.live_jgv.betting.domain.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SuspensionKafkaListenerTest {
    @Test void rawBytesAndDuplicateHeadersArePreservedBeforeReturningSuccess() throws Exception {
        var store=mock(SuspensionStore.class);var decoder=mock(EventDecoder.class);
        var invalid=DecodedInput.invalid(DecodedInput.Reason.INVALID_EVENT);when(decoder.decode(any())).thenReturn(invalid);
        when(store.handle(any(),eq(invalid))).thenReturn(SuspensionStore.Outcome.QUARANTINED);
        var listener=new SuspensionKafkaListener(new SuspensionService(store,decoder),"source/1");
        var record=new ConsumerRecord<byte[],byte[]>("match_events",1,22,new byte[]{1},new byte[]{2});
        record.headers().add("x",new byte[]{3});record.headers().add("x",null);
        listener.receive(record);
        var input=ArgumentCaptor.forClass(InputRecord.class);verify(store).handle(input.capture(),eq(invalid));
        assertEquals(22,input.getValue().offset());assertArrayEquals(new byte[]{2},input.getValue().value());
        var headers=new com.fasterxml.jackson.databind.ObjectMapper().readTree(input.getValue().headers());
        assertEquals(2,headers.size());assertEquals("Aw==",headers.get(0).path("value_base64").asText());
        assertTrue(headers.get(1).path("value_base64").isNull());
    }
    @Test void databaseFailureIsNotSwallowedByListener() {
        var store=mock(SuspensionStore.class);var decoder=mock(EventDecoder.class);
        var invalid=DecodedInput.invalid(DecodedInput.Reason.MALFORMED_JSON);when(decoder.decode(any())).thenReturn(invalid);
        when(store.handle(any(),any())).thenThrow(new DataAccessResourceFailureException("unavailable"));
        var listener=new SuspensionKafkaListener(new SuspensionService(store,decoder),"s");
        assertThrows(DataAccessResourceFailureException.class,()->listener.receive(new ConsumerRecord<>("t",0,0,null,null)));
    }
}
