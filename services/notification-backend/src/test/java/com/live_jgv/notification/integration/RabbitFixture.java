package com.live_jgv.notification.integration;

import com.live_jgv.notification.domain.ScoreUpdate;
import com.live_jgv.notification.infrastructure.amqp.*;
import com.live_jgv.notification.infrastructure.config.RabbitConfiguration;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;

/** Real connections only, constructed after authorization. No automatic topology/cleanup. */
public final class RabbitFixture implements AutoCloseable {
    public static final Set<String> ACTIONS=Set.of("database-read","database-write","migrate","declare","consume","publish","channel-failure","topology-failure");
    public final PostgresFixture db;
    public final CachingConnectionFactory factory;
    public final RabbitAdmin admin;
    public final RabbitTemplate template;
    public final String queue,match;
    public final Binding dlqBinding;
    public final AtomicBoolean stopped=new AtomicBoolean();
    public final IntegrationEvidence evidence;
    private final List<Channel> channels=new ArrayList<>();
    public RabbitFixture(PostgresFixture db,String scenario,IntegrationEvidence evidence) {
        db.authorization.require(ACTIONS);
        this.db=db; this.evidence=evidence;
        String suffix=scenario+"_"+UUID.randomUUID().toString().replace("-","");
        queue=db.settings.queuePrefix()+"."+suffix; match=db.settings.match(suffix);
        factory=new CachingConnectionFactory("127.0.0.1",db.settings.rabbitPort());
        factory.setUsername(db.settings.rabbitUser()); factory.setPassword(db.settings.rabbitPassword()); factory.setVirtualHost(db.settings.vhost());
        factory.setConnectionTimeout(5000); factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); factory.setPublisherReturns(true);
        factory.getRabbitConnectionFactory().setHandshakeTimeout(5000);
        factory.getRabbitConnectionFactory().setChannelRpcTimeout(5000);
        factory.getRabbitConnectionFactory().setRequestedHeartbeat(5);
        admin=new RabbitAdmin(factory); admin.setAutoStartup(false);
        template=new RabbitTemplate(factory); template.setMandatory(true);
        try {
            var topology=RabbitConfiguration.topology(db.settings.exchange(),queue,match);
            for (var item:topology.getDeclarables()) {
                if(item instanceof Exchange exchange) admin.declareExchange(exchange);
                else if(item instanceof org.springframework.amqp.core.Queue input) admin.declareQueue(input);
                else if(item instanceof Binding binding) admin.declareBinding(binding);
            }
        } catch(RuntimeException failedSetup) { factory.destroy(); throw failedSetup; }
        dlqBinding=new Binding(queue+".dlq",Binding.DestinationType.QUEUE,queue+".dlx","rejected",null);
    }
    public Channel channel() {
        Channel channel=factory.createConnection().createChannel(false); channels.add(channel); return channel;
    }
    public ScoreUpdateListener listener(String failureExchange,Runnable stop) {
        return new ScoreUpdateListener(db.service(),db.codec,new ConfirmedDlqRecovery(template,failureExchange,Duration.ofSeconds(3)),
                ()->{stopped.set(true); stop.run();},Thread::sleep,3,10);
    }
    public void publish(ScoreUpdate update) throws Exception { publish(db.codec.encode(update).getBytes(StandardCharsets.UTF_8),update.eventId()); }
    public void publish(byte[] body,String eventId) throws Exception {
        var properties=new MessageProperties(); properties.setContentType("application/json"); properties.setMessageId(eventId);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation=new CorrelationData(UUID.randomUUID().toString());
        template.send(db.settings.exchange(),"score."+match,new Message(body,properties),correlation);
        if (!correlation.getFuture().get(5,TimeUnit.SECONDS).ack() || correlation.getReturned()!=null) throw new AssertionError("Source publication not confirmed/routed");
        evidence.record("published",Map.of("event_id",eventId,"result","CONFIRMED"));
    }
    public GetResponse delivery(Channel channel,String target) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do { var response=channel.basicGet(target,false); if(response!=null) return response; Thread.sleep(20); } while(System.nanoTime()<deadline);
        throw new AssertionError("Owned queue did not deliver in time");
    }
    public Message message(GetResponse response) {
        var properties=new DefaultMessagePropertiesConverter().toMessageProperties(response.getProps(),response.getEnvelope(),"UTF-8");
        return new Message(response.getBody(),properties);
    }
    public void process(GetResponse response,Channel channel,Runnable beforeAck) throws Exception {
        listener(queue+".dlx",()->{}).receive(message(response),observed(channel,beforeAck,false));
    }
    public Channel observed(Channel delegate,Runnable beforeAck,boolean closeBeforeAck) {
        return (Channel)Proxy.newProxyInstance(Channel.class.getClassLoader(),new Class<?>[]{Channel.class},(proxy,method,args)->{
            if(method.getName().equals("basicAck")) {
                beforeAck.run();
                if(closeBeforeAck) {
                    // Closing a Spring cached proxy can merely return it to the cache.
                    // Abort the actual AMQP channel to produce genuine broker redelivery.
                    if(delegate instanceof org.springframework.amqp.rabbit.connection.ChannelProxy target) target.getTargetChannel().abort();
                    else delegate.abort();
                    throw new java.io.IOException("Deliberate owned-channel interruption before ACK");
                }
            }
            try {
                Object result=method.invoke(delegate,args);
                if(method.getName().equals("basicAck")) evidence.record("ack",Map.of("ack",true));
                if(method.getName().equals("basicNack")) evidence.record("nack",Map.of("nack",true));
                return result;
            } catch(InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    public int ready(Channel channel,String target) throws Exception { return channel.queueDeclarePassive(target).getMessageCount(); }
    @Override public void close() {
        for (var channel:channels) try { if(channel.isOpen()) channel.close(); } catch(Exception ignored) { }
        factory.destroy(); // Only connections owned by this fixture; never delete/purge queues.
    }
}
