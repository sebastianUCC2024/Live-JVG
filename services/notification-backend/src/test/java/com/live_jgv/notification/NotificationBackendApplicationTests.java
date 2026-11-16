package com.live_jgv.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties={"notification.storage.enabled=false","notification.messaging.enabled=false","spring.flyway.enabled=false","spring.rabbitmq.dynamic=false","debug=false","logging.level.root=WARN"})
class NotificationBackendApplicationTests {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext context;

    @Test void readinessIsUnavailableWhileLivenessRemainsUpWithInfrastructureDisabled() {
        var endpoint=context.getBean(org.springframework.boot.health.actuate.endpoint.HealthEndpoint.class);
        org.junit.jupiter.api.Assertions.assertEquals(org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE,
                endpoint.healthForPath("readiness").getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(org.springframework.boot.health.contributor.Status.UP,
                endpoint.healthForPath("liveness").getStatus());
    }

	@Test
	void contextLoads() {
		org.junit.jupiter.api.Assertions.assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(context.getBeansOfType(org.flywaydb.core.Flyway.class).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(context.getBeansOfType(com.live_jgv.notification.infrastructure.amqp.ScoreUpdateListener.class).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(context.getBeansOfType(org.springframework.amqp.rabbit.core.RabbitAdmin.class).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(context.getBeansOfType(org.springframework.amqp.core.Declarables.class).isEmpty());
	}

}
