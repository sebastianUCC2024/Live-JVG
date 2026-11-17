package com.live_jgv.worker.academic;
import org.junit.jupiter.api.*;import org.springframework.amqp.core.*;import org.springframework.amqp.rabbit.core.RabbitTemplate;import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.time.Instant;import java.nio.charset.StandardCharsets;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class AcademicListenerTest {
 AcademicCommandStore store;RabbitTemplate rabbit;AcademicWorkerListener listener;
 @BeforeEach void setup(){store=mock(AcademicCommandStore.class);rabbit=mock(RabbitTemplate.class);listener=new AcademicWorkerListener(new AcademicCommandHandler(store,new AcademicCommandCodec()),store,new AcademicRabbitSender(rabbit,"betting_commands.dlx","rejected",1),"betting_commands","academic-test/1");}
 Message message(boolean valid){var props=new MessageProperties();props.setReceivedExchange("");props.setReceivedRoutingKey("betting_commands");props.setMessageId("cmd");return new Message(valid?new AcademicCommand(1,"cmd","match","event",AcademicCommand.CommandType.RESUME_BETS,Instant.EPOCH).json().getBytes(StandardCharsets.UTF_8):new byte[]{(byte)255},props);}
 @Test void validDeliveryReturnsAfterPersistenceWithoutPublishing(){when(store.record(any(),any())).thenReturn(AcademicCommandStore.Outcome.RECORDED);listener.receive(message(true));verify(store).record(any(),any());verify(rabbit,never()).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));}
 @Test void persistenceFailureCannotReturnNormallyForAck(){when(store.record(any(),any())).thenThrow(new IllegalStateException("db unavailable"));assertThrows(IllegalStateException.class,()->listener.receive(message(true)));}
 @Test void malformedMessageRequiresDurableAuditAndConfirmedDLQ(){when(store.quarantine(any(),anyString())).thenReturn(AcademicCommandStore.Outcome.QUARANTINED);doAnswer(i->{Message m=i.getArgument(2);assertArrayEquals(new byte[]{(byte)255},m.getBody());CorrelationData c=i.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(eq("betting_commands.dlx"),eq("rejected"),any(Message.class),any(CorrelationData.class));listener.receive(message(false));var order=inOrder(store,rabbit);order.verify(store).quarantine(any(),eq("INVALID_ACADEMIC_COMMAND"));order.verify(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));}
 @Test void unconfirmedDlqDoesNotAck(){when(store.quarantine(any(),anyString())).thenReturn(AcademicCommandStore.Outcome.QUARANTINED);assertThrows(IllegalStateException.class,()->listener.receive(message(false)));}
 @Test void identityConflictIsAuditedThenForwardedToDlq(){when(store.record(any(),any())).thenReturn(AcademicCommandStore.Outcome.IDENTITY_CONFLICT);assertThrows(IllegalStateException.class,()->listener.receive(message(true)));verify(rabbit).send(eq("betting_commands.dlx"),eq("rejected"),any(Message.class),any(CorrelationData.class));}
 @Test void unexpectedExchangeCannotInvokeDomainRecord(){var message=message(true);message.getMessageProperties().setReceivedExchange("wrong");when(store.quarantine(any(),anyString())).thenReturn(AcademicCommandStore.Outcome.QUARANTINED);assertThrows(IllegalStateException.class,()->listener.receive(message));verify(store).quarantine(any(),eq("UNEXPECTED_ACADEMIC_DESTINATION"));verify(store,never()).record(any(),any());}
 @Test void eachConsumerInstanceAddsItsOwnAuditIdentity(){
  when(store.record(any(),any())).thenReturn(AcademicCommandStore.Outcome.RECORDED);
  listener.receive(message(true));
  new AcademicWorkerListener(new AcademicCommandHandler(store,new AcademicCommandCodec()),store,new AcademicRabbitSender(rabbit,"betting_commands.dlx","rejected",1),"betting_commands","academic-test/1").receive(message(true));
  var evidence=org.mockito.ArgumentCaptor.forClass(com.live_jgv.worker.domain.TransportMessage.class);
  verify(store,times(2)).record(any(),evidence.capture());
  assertNotEquals(evidence.getAllValues().get(0).headers(),evidence.getAllValues().get(1).headers());
  assertTrue(evidence.getAllValues().get(0).headers().contains("academic_consumer_instance_id"));
 }
 @Test void durableTopologyIncludesSeparateDeadLetterBinding(){var topology=AcademicTopologyConfiguration.topology("betting_commands");assertEquals(4,topology.getDeclarables().size());for(var d:topology.getDeclarables())if(d instanceof Queue q){assertTrue(q.isDurable());assertFalse(q.isAutoDelete());if(q.getName().equals("betting_commands")){assertEquals("betting_commands.dlx",q.getArguments().get("x-dead-letter-exchange"));assertEquals("rejected",q.getArguments().get("x-dead-letter-routing-key"));}}}
}
