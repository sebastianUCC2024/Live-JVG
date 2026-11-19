package com.live_jgv.notification;
import com.live_jgv.notification.domain.*;
import com.live_jgv.notification.infrastructure.json.*;
import com.live_jgv.notification.infrastructure.config.RabbitConfiguration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import static org.junit.jupiter.api.Assertions.*;
public class LiveAlertContractTest {
    public static LiveAlertV1 wire() {
        var score=new ScoreUpdate("match","goal",ScoreUpdate.EventType.GOAL,1,0,ScoreUpdate.VarStatus.IN_PROGRESS,Instant.parse("2026-11-17T10:00:00.123456789Z"),null);
        return new LiveAlertV1("1.0",UUID.fromString("11111111-1111-4111-8111-111111111111"),"match","goal",score.eventType(),"Gol registrado. Revisi\u00f3n VAR en curso.",score.updatedAt(),null,score);
    }
    @Test void strictRoundTripPreservesSnapshotAndNanoTimestamp() {
        var codec=new LiveAlertCodec();assertEquals(wire(),codec.decode(codec.encode(wire()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void rejectsIncoherentIdentityAndSnapshotTypes() {
        var codec=new LiveAlertCodec();String json=codec.encode(wire());
        for(String invalid:List.of(json.replace("\"source_event_id\":\"goal\"","\"source_event_id\":\"other\""),json.replace("\"home_score\":1","\"home_score\":\"1\""),json.replace("\"contract_version\":\"1.0\"","\"contract_version\":\"2.0\""),json.substring(0,json.length()-1)+",\"extra\":true}",json+" {}")) {
            assertThrows(InvalidUpdateException.class,()->codec.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
    @Test void topologyIsDurableFanoutWithoutTopicBindingOrDashboardQueue() {
        var items=RabbitConfiguration.alertTopology("notification.fixture").getDeclarables();
        assertTrue(items.stream().anyMatch(x->x instanceof FanoutExchange e && e.getName().equals("live_alerts") && e.isDurable()));
        assertFalse(items.stream().anyMatch(x->x instanceof TopicExchange));
        assertTrue(items.stream().anyMatch(x->x instanceof Binding b && b.getExchange().equals("live_alerts") && b.getRoutingKey().isEmpty()));
    }
    @Test void offRegistersNoRabbitAdminTopologyOrConsumer() {
        new ApplicationContextRunner().withUserConfiguration(RabbitConfiguration.class)
            .withPropertyValues("notification.messaging.enabled=false","notification.messaging.live-alerts-enabled=true")
            .run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(RabbitAdmin.class).isEmpty());assertTrue(context.getBeansOfType(Declarables.class).isEmpty());});
    }
}
