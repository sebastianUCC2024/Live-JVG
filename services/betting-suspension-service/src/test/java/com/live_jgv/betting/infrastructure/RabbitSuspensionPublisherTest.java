package com.live_jgv.betting.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

/** Unit tests of confirmation handling only; these are not broker integration evidence. */
class RabbitSuspensionPublisherTest {
    @Test void confirmedPublicationUsesPersistentStoredImageAndStableMessageIdentity() {
        var rabbit=mock(RabbitTemplate.class);
        doAnswer(call->{ ((CorrelationData)call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true,null));return null; })
            .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        var publisher=new RabbitSuspensionPublisher(rabbit,"approved-exchange","approved-key",10);
        publisher.publish("stable-id","stored-json");
        var message=ArgumentCaptor.forClass(Message.class);verify(rabbit).send(eq("approved-exchange"),eq("approved-key"),message.capture(),any(CorrelationData.class));
        assertEquals("stable-id",message.getValue().getMessageProperties().getMessageId());
        assertEquals(MessageDeliveryMode.PERSISTENT,message.getValue().getMessageProperties().getDeliveryMode());
        assertEquals("stored-json",new String(message.getValue().getBody(),java.nio.charset.StandardCharsets.UTF_8));
        verify(rabbit).setMandatory(true);
    }
    @Test void negativeConfirmationOrReturnedMessageIsNotSuccess() {
        var rabbit=mock(RabbitTemplate.class);
        doAnswer(call->{ ((CorrelationData)call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false,"rejected"));return null; })
            .when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertThrows(IllegalStateException.class,()->new RabbitSuspensionPublisher(rabbit,"x","r",10).publish("id","{}"));
        reset(rabbit);
        doAnswer(call->{
            var correlation=(CorrelationData)call.getArgument(3);
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0]),312,"NO_ROUTE","x","r"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertThrows(IllegalStateException.class,()->new RabbitSuspensionPublisher(rabbit,"x","r",10).publish("id","{}"));
    }
    @Test void timeoutIsUncertainAndDoesNotInventAConfirmation() {
        var rabbit=mock(RabbitTemplate.class);
        assertThrows(IllegalStateException.class,()->new RabbitSuspensionPublisher(rabbit,"x","r",1).publish("id","{}"));
    }
    @Test void noUnapprovedDefaultDestinationIsAllowed() {
        assertThrows(IllegalArgumentException.class,()->new RabbitSuspensionPublisher(mock(RabbitTemplate.class),"","r",10));
    }
}
