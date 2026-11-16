package com.live_jgv.notification.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.live_jgv.notification.domain.ScoreUpdate;
import com.live_jgv.notification.infrastructure.health.ConsumerReadiness;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.event.AmqpEvent;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

@Tag("external-integration")
public class NotificationRabbitIT {
    @org.junit.jupiter.api.extension.RegisterExtension static final IntegrationObserver observer=new IntegrationObserver();
    static PostgresFixture db; static IntegrationEvidence evidence;
    @BeforeAll static void authorizedSetup() throws Exception {
        var authorization=IntegrationGate.authorize(RabbitFixture.ACTIONS);
        evidence=new IntegrationEvidence(authorization.settings()); observer.use(evidence); db=new PostgresFixture(authorization,true);
    }
    @ParameterizedTest @EnumSource(value=ScoreUpdate.EventType.class,names={"GOAL","VAR_CHECK","GOAL_ANNULLED","MATCH_CONTINUES"})
    void realMessagesCommitBeforeAckAndFollowApprovedPolicy(ScoreUpdate.EventType type) throws Exception {
        try(var fixture=new RabbitFixture(db,"event",evidence)) {
            Channel channel=fixture.channel(); var update=db.update(fixture.match,"event-"+type,type);
            assertEquals(0,fixture.ready(channel,fixture.queue));
            assertFalse(fixture.queue.equals("mvp-dashboard-scores"));
            fixture.publish(update);
            var response=fixture.delivery(channel,fixture.queue);
            fixture.process(response,channel,()->{
                assertEquals(1,db.inbox(fixture.match));
                assertEquals(type==ScoreUpdate.EventType.MATCH_CONTINUES ? 0 : 1,db.alerts(fixture.match));
            });
            assertEquals(0,fixture.ready(channel,fixture.queue)); assertEquals(0,fixture.ready(channel,fixture.queue+".dlq"));
            evidence.record("event-policy",Map.of("event_id",update.eventId(),"rows",db.alerts(fixture.match),"result",type.toString()));
        }
    }
    @Test void genuineBrokerRedeliveryAfterCommitDoesNotDuplicateAlert() throws Exception {
        try(var fixture=new RabbitFixture(db,"redelivery",evidence)) {
            var channel=fixture.channel(); var update=db.update(fixture.match,"redelivered-event",ScoreUpdate.EventType.GOAL); fixture.publish(update);
            var first=fixture.delivery(channel,fixture.queue);
            assertThrows(java.io.IOException.class,()->fixture.listener(fixture.queue+".dlx",()->{}).receive(fixture.message(first),
                    fixture.observed(channel,()->assertEquals(1,db.alerts(fixture.match)),true)));
            String original=db.store.query(fixture.match,null,50).items().get(0).alertId().toString();
            var fresh=fixture.channel(); var redelivered=fixture.delivery(fresh,fixture.queue);
            assertTrue(redelivered.getEnvelope().isRedeliver()); fixture.process(redelivered,fresh,()->{});
            assertEquals(1,db.alerts(fixture.match)); assertEquals(original,db.store.query(fixture.match,null,50).items().get(0).alertId().toString());
            evidence.record("redelivery",Map.of("event_id",update.eventId(),"alert_id",original,"redelivered",true));
        }
    }
    @Test void invalidMessageIsConfirmedInDlqBeforeOriginalAck() throws Exception {
        try(var fixture=new RabbitFixture(db,"invalid",evidence)) {
            Channel channel=fixture.channel(); byte[] invalid="{broken".getBytes(StandardCharsets.UTF_8); fixture.publish(invalid,"invalid-event");
            var delivery=fixture.delivery(channel,fixture.queue);
            fixture.process(delivery,channel,()->{
                try { assertEquals(1,fixture.ready(channel,fixture.queue+".dlq")); }
                catch(Exception error) { throw new AssertionError(error); }
            });
            var failed=fixture.delivery(channel,fixture.queue+".dlq"); assertArrayEquals(invalid,failed.getBody());
            // Leave the DLQ delivery unacknowledged; fixture close retains it for review.
            var good=db.update(fixture.match,"after-invalid",ScoreUpdate.EventType.GOAL); fixture.publish(good);
            fixture.process(fixture.delivery(channel,fixture.queue),channel,()->assertEquals(1,db.alerts(fixture.match)));
            evidence.record("invalid-dlq",Map.of("event_id","invalid-event","dlq_messages",1,"ack",true));
        }
    }
    @Test void unroutedDlqTransferRetainsOriginalAndCanRecover() throws Exception {
        try(var fixture=new RabbitFixture(db,"dlq-return",evidence)) {
            Channel channel=fixture.channel();
            fixture.admin.removeBinding(fixture.dlqBinding); // Only this unique fixture's binding; explicitly approved fault.
            try {
                fixture.publish("broken".getBytes(StandardCharsets.UTF_8),"unrouted-event");
                var response=fixture.delivery(channel,fixture.queue);
                fixture.listener(fixture.queue+".dlx",()->{}).receive(fixture.message(response),fixture.observed(channel,()->fail("Must not ACK without custody"),false));
                assertTrue(fixture.stopped.get()); assertEquals(1,fixture.ready(channel,fixture.queue));
                assertEquals(0,fixture.ready(channel,fixture.queue+".dlq"));
            } finally { fixture.admin.declareBinding(fixture.dlqBinding); }
            fixture.process(fixture.delivery(channel,fixture.queue),channel,()->{});
            assertEquals(0,fixture.ready(channel,fixture.queue)); assertEquals(1,fixture.ready(channel,fixture.queue+".dlq"));
            evidence.record("dlq-recovery",Map.of("nack",true,"dlq_messages",1,"result","CUSTODY_RESTORED"));
        }
    }
    @Test void duplicateAndConflictingPayloadKeepOriginalAlert() throws Exception {
        try(var fixture=new RabbitFixture(db,"duplicate-conflict",evidence)) {
            Channel channel=fixture.channel(); var update=db.update(fixture.match,"same-id",ScoreUpdate.EventType.GOAL);
            fixture.publish(update); fixture.process(fixture.delivery(channel,fixture.queue),channel,()->{});
            String original=db.store.query(fixture.match,null,50).items().get(0).alertId().toString();
            fixture.publish(update); fixture.process(fixture.delivery(channel,fixture.queue),channel,()->{});
            var conflict=new ScoreUpdate(update.matchId(),update.eventId(),update.eventType(),2,0,update.varStatus(),update.updatedAt(),null);
            fixture.publish(conflict); fixture.process(fixture.delivery(channel,fixture.queue),channel,()->{});
            assertEquals(1,db.alerts(fixture.match)); assertEquals(1,fixture.ready(channel,fixture.queue+".dlq"));
            assertEquals(original,db.store.query(fixture.match,null,50).items().get(0).alertId().toString());
            evidence.record("duplicate-conflict",Map.of("event_id",update.eventId(),"alert_id",original,"rows",1,"dlq_messages",1));
        }
    }
    @Test void missingDlqExchangeProducesRealChannelFailureWithoutInputAck() throws Exception {
        try(var fixture=new RabbitFixture(db,"confirm-failure",evidence)) {
            Channel channel=fixture.channel(); fixture.publish("broken".getBytes(StandardCharsets.UTF_8),"confirm-failure-event");
            var response=fixture.delivery(channel,fixture.queue);
            fixture.listener(fixture.queue+".missing",()->{}).receive(fixture.message(response),fixture.observed(channel,()->fail("Must not ACK failed publication"),false));
            assertTrue(fixture.stopped.get()); assertEquals(1,fixture.ready(channel,fixture.queue));
            evidence.record("confirm-failure",Map.of("nack",true,"ready_messages",1,"result","RETAINED"));
        }
    }
    @Test void ownedConsumerStopsAndNewConsumerRecoversQueuedMessages() throws Exception {
        try(var fixture=new RabbitFixture(db,"consumer-restart",evidence)) {
            var monitor=new ConsumerReadiness(fixture.queue);
            var first=container(fixture,monitor); var second=new AtomicReference<SimpleMessageListenerContainer>();
            try {
                first.start(); await(()->monitor.accepted());
                fixture.publish(db.update(fixture.match,"before-restart",ScoreUpdate.EventType.GOAL)); await(()->db.alerts(fixture.match)==1);
                first.stop(); monitor.invalidate();
                fixture.publish(db.update(fixture.match,"after-restart",ScoreUpdate.EventType.VAR_CHECK));
                second.set(container(fixture,monitor)); second.get().start();
                await(()->monitor.accepted() && db.alerts(fixture.match)==2);
                evidence.record("consumer-restart",Map.of("result","RECOVERED","rows",db.alerts(fixture.match)));
            } finally { first.stop(); if(second.get()!=null) second.get().stop(); }
        }
    }
    private static SimpleMessageListenerContainer container(RabbitFixture fixture,ConsumerReadiness monitor) {
        var container=new SimpleMessageListenerContainer(fixture.factory);
        container.setQueueNames(fixture.queue); container.setAcknowledgeMode(AcknowledgeMode.MANUAL); container.setPrefetchCount(1);
        container.setConcurrentConsumers(1); container.setMaxConcurrentConsumers(1);
        container.setShutdownTimeout(5000); container.setApplicationEventPublisher(event->{if(event instanceof AmqpEvent amqp) monitor.onApplicationEvent(amqp);});
        var listener=fixture.listener(fixture.queue+".dlx",()->{monitor.invalidate(); container.stop(()->{});});
        container.setMessageListener((ChannelAwareMessageListener)listener::receive);
        return container;
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),"Owned integration condition timed out");
    }
}
