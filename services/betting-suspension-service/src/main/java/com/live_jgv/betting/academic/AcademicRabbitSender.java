package com.live_jgv.betting.academic;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Publica por AMQP con mandatory y confirms; un timeout deja el resultado incierto. */
public final class AcademicRabbitSender {
    private final RabbitTemplate rabbit;private final String exchange,routing;private final long timeout;
    public AcademicRabbitSender(RabbitTemplate rabbit,String exchange,String routing,long timeout) {
        if(exchange==null||routing==null||routing.isBlank()||timeout<1)throw new IllegalArgumentException("Academic destination and timeout required");
        this.rabbit=rabbit;this.exchange=exchange;this.routing=routing;this.timeout=timeout;rabbit.setMandatory(true);
    }
    public void send(String id,byte[] body,Map<String,Object> headers) {
        var props=new MessageProperties();props.setMessageId(id);props.setContentType("application/json");
        props.setContentEncoding("UTF-8");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.getHeaders().putAll(headers);
        var correlation=new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(exchange,routing,new Message(body,props),correlation);
            var confirm=correlation.getFuture().get(timeout,TimeUnit.MILLISECONDS);
            if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("Academic publication not routed and confirmed");
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Academic publication interrupted");
        } catch(Exception uncertain){throw new IllegalStateException("Academic publication outcome uncertain");}
    }
}
