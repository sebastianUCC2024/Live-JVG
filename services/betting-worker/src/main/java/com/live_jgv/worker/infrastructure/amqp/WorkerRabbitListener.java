package com.live_jgv.worker.infrastructure.amqp;

import com.live_jgv.worker.application.WorkerService;
import com.live_jgv.worker.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import java.util.*;

/** Recibe comandos de worker.rabbit.queue y ejecuta WorkerService; conserva mensajes de destinos inválidos. */
public class WorkerRabbitListener {
    private final WorkerService service;private final String source;private final String expectedExchange;
    private final ObjectMapper mapper=new ObjectMapper();
    public WorkerRabbitListener(WorkerService service,String source,String exchange){
        if(source==null||source.isBlank()||exchange==null||exchange.isBlank())throw new IllegalArgumentException("Approved source and exchange required");
        this.service=service;this.source=source;this.expectedExchange=exchange;
    }
    @RabbitListener(id="simulatedBettingWorker",queues="${worker.rabbit.queue}",containerFactory="workerRabbitFactory",autoStartup="false")
    public void receive(Message message){
        var props=message.getMessageProperties();String exchange=Objects.toString(props.getReceivedExchange(),"");
        String routing=Objects.toString(props.getReceivedRoutingKey(),"");
        final String headers;
        try{headers=mapper.writeValueAsString(props.getHeaders());}
        catch(Exception failure){throw new IllegalStateException("Cannot preserve message headers");}
        var input=new TransportMessage(source,props.getMessageId(),exchange,routing,message.getBody(),headers);
        // Registra el destino inesperado como entrada inválida persistente.
        if(!expectedExchange.equals(exchange)){
            service.reject(input,"UNEXPECTED_EXCHANGE");
            return;
        }
        service.receive(input);
    }
}
