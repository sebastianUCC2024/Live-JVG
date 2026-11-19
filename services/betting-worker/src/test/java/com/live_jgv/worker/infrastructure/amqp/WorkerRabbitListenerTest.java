package com.live_jgv.worker.infrastructure.amqp;

import com.live_jgv.worker.application.*;
import com.live_jgv.worker.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerRabbitListenerTest {
    @Test void validRouteReturnsOnlyAfterDurableProcessing(){
        var store=mock(WorkerStore.class);var decoder=mock(CommandDecoder.class);var message=new Message(new byte[]{1});
        message.getMessageProperties().setReceivedExchange("approved");message.getMessageProperties().setReceivedRoutingKey("key");
        message.getMessageProperties().setHeader("bytes",new byte[]{2});
        when(decoder.decode(any())).thenReturn(DecodedCommand.invalid("INVALID_COMMAND"));
        new WorkerRabbitListener(new WorkerService(store,decoder,new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation()),"s","approved").receive(message);
        var raw=ArgumentCaptor.forClass(TransportMessage.class);verify(store).handle(raw.capture(),any());
        assertArrayEquals(new byte[]{1},raw.getValue().body());assertEquals("approved",raw.getValue().exchange());assertTrue(raw.getValue().headers().contains("bytes"));
    }
    @Test void wrongExchangeIsQuarantinedRatherThanProcessingAValidLookingCommand(){
        var store=mock(WorkerStore.class);var decoder=mock(CommandDecoder.class);
        var message=new Message(new byte[]{1});message.getMessageProperties().setReceivedExchange("wrong");
        new WorkerRabbitListener(new WorkerService(store,decoder,new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation()),"s","approved").receive(message);
        var decoded=ArgumentCaptor.forClass(DecodedCommand.class);verify(store).handle(any(),decoded.capture());
        assertEquals("UNEXPECTED_EXCHANGE",decoded.getValue().error());verifyNoInteractions(decoder);
    }
    @Test void databaseFailureIsNotSwallowedSoAutoAckCannotSucceed(){
        var store=mock(WorkerStore.class);when(store.handle(any(),any())).thenThrow(new DataAccessResourceFailureException("down"));
        var listener=new WorkerRabbitListener(new WorkerService(store,mock(CommandDecoder.class),new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation()),"s","approved");
        assertThrows(DataAccessResourceFailureException.class,()->listener.receive(new Message(new byte[0])));
    }
}
