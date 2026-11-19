package com.live_jgv.worker.integration;

import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Passive declarations only: no queue creation, consumption, purge or publication. */
@EnabledIfEnvironmentVariable(named="DEV3_RUN_ISOLATED_BROKER_TESTS",matches="true")
class PreparedRabbitBrokerIntegrationTest {
    @Test void approvedIsolatedCommandDestinationExists() throws Exception {
        assertEquals("I_APPROVE_ISOLATED_BROKER_PROBES",System.getenv("DEV3_BROKER_APPROVAL"));
        String host=System.getenv("DEV3_RABBITMQ_HOST"),queue=System.getenv("DEV3_WORKER_QUEUE"),exchange=System.getenv("DEV3_WORKER_EXCHANGE");
        assertEquals("127.0.0.1",host);assertNotNull(queue);assertNotNull(exchange);
        assertTrue(queue.matches("dev3_it_[A-Za-z0-9_-]+"));assertTrue(exchange.matches("dev3_it_[A-Za-z0-9_-]+"));
        int port=Integer.parseInt(System.getenv("DEV3_RABBITMQ_PORT"));assertTrue(port>1024&&port<65536);assertNotEquals(5672,port);
        var factory=new ConnectionFactory();factory.setHost(host);factory.setPort(port);
        factory.setUsername(java.util.Objects.requireNonNull(System.getenv("DEV3_RABBITMQ_USERNAME")));
        factory.setPassword(java.util.Objects.requireNonNull(System.getenv("DEV3_RABBITMQ_PASSWORD")));
        factory.setVirtualHost(java.util.Objects.requireNonNull(System.getenv("DEV3_RABBITMQ_VHOST")));
        factory.setAutomaticRecoveryEnabled(false);factory.setConnectionTimeout(5000);factory.setHandshakeTimeout(5000);
        try(var connection=factory.newConnection();var channel=connection.createChannel()){
            channel.exchangeDeclarePassive(exchange);
            assertEquals(queue,channel.queueDeclarePassive(queue).getQueue());
        }
    }
}
