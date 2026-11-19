package com.live_jgv.worker.infrastructure.amqp;

import com.live_jgv.worker.application.WorkerService;
import com.live_jgv.worker.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import java.util.*;

/**
 * Executes WorkerService for the configured private command queue after explicit startup gates.
 * Preserves transport identity and headers; foreign exchanges become durable rejected input.
 * Acknowledgment, retry and connection policy belong to the worker container configuration.
 * This command channel is separate from Kafka's sporting history and grants no market authority.
 */
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
        // Keep destination mismatch as durable invalid input rather than processing a foreign command.
        if(!expectedExchange.equals(exchange)){
            service.reject(input,"UNEXPECTED_EXCHANGE");
            return;
        }
        service.receive(input);
    }
}
