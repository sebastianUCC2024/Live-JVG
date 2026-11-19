package com.live_jgv.worker.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.worker.domain.TransportMessage;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import java.util.*;

/** Confirma la cola betting_commands tras procesar el comando y asegurar los rechazos en la DLQ. */
public final class AcademicWorkerListener {
    private final String instanceId=UUID.randomUUID().toString();
    private final AcademicCommandHandler handler;private final AcademicCommandStore store;
    private final AcademicRabbitSender dlq;private final String queue,source;
    public AcademicWorkerListener(AcademicCommandHandler handler,AcademicCommandStore store,AcademicRabbitSender dlq,String queue,String source){
        if(queue==null||queue.isBlank()||source==null||source.isBlank())throw new IllegalArgumentException("Academic queue/source required");
        this.handler=handler;this.store=store;this.dlq=dlq;this.queue=queue;this.source=source;
    }
    @RabbitListener(id="academicBettingWorker",queues="${academic.rabbit.queue:betting_commands}",containerFactory="academicRabbitFactory",autoStartup="false")
    public void receive(Message message) {
        var props=message.getMessageProperties();final String headers;
        try{var auditHeaders=new HashMap<String,Object>(props.getHeaders());auditHeaders.put("academic_consumer_instance_id",instanceId);headers=new ObjectMapper().writeValueAsString(auditHeaders);}
        catch(Exception failure){throw new IllegalStateException("Cannot preserve academic transport headers");}
        var input=new TransportMessage(source,props.getMessageId(),Objects.toString(props.getReceivedExchange(),""),
                Objects.toString(props.getReceivedRoutingKey(),""),message.getBody(),headers);
        var outcome=!input.exchange().isEmpty()||!queue.equals(input.routingKey())
                ?store.quarantine(input,"UNEXPECTED_ACADEMIC_DESTINATION"):handler.receive(input);
        if(outcome==AcademicCommandStore.Outcome.QUARANTINED||outcome==AcademicCommandStore.Outcome.IDENTITY_CONFLICT) {
            var copy=new HashMap<String,Object>(props.getHeaders());copy.put("academic_outcome",outcome.name());
            copy.put("original_exchange",input.exchange());copy.put("original_routing_key",input.routingKey());
            dlq.send(props.getMessageId()==null?UUID.randomUUID().toString():props.getMessageId(),message.getBody(),copy);
        }
    }
}
