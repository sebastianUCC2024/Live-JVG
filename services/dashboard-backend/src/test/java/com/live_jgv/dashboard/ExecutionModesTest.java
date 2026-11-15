package com.live_jgv.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

/** Resolves real Boot configuration without creating datasource, Rabbit or web beans. */
class ExecutionModesTest {
    @Test void gatewayTimeoutsAreExternallyConfigurable(){
        context.withPropertyValues("PRODUCER_CONNECT_TIMEOUT=350ms","PRODUCER_READ_TIMEOUT=1200ms").run(app->{
            assertEquals("350ms",app.getEnvironment().getProperty("producer.connect-timeout"));
            assertEquals("1200ms",app.getEnvironment().getProperty("producer.read-timeout"));});
    }
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());
    @Test void durableDefaultsAreUnambiguousAndWritingRemainsDisabled(){
        context.run(app->{var env=app.getEnvironment();assertEquals("8080",env.getProperty("server.port"));
            assertEquals("5673",env.getProperty("spring.rabbitmq.port"));assertEquals("false",env.getProperty("simulator.write-enabled"));});
    }
    @Test void memoryPreservesLegacyPortsAndDisablesDatabaseAutoconfiguration(){
        context.withPropertyValues("spring.profiles.active=memory").run(app->{var env=app.getEnvironment();
            assertEquals("8081",env.getProperty("server.port"));assertEquals("5672",env.getProperty("spring.rabbitmq.port"));
            assertTrue(env.getProperty("spring.autoconfigure.exclude").contains("DataSourceAutoConfiguration"));});
    }
    @Test void explicitAliasesAndLocalOptInResolveWithoutChangingPublishedPorts(){
        context.withPropertyValues("PORT=8080","DASHBOARD_PORT=8099","RABBITMQ_PORT=5674","SIMULATOR_WRITE_ENABLED=true")
            .run(app->{var env=app.getEnvironment();assertEquals("8099",env.getProperty("server.port"));
                assertEquals("5674",env.getProperty("spring.rabbitmq.port"));assertEquals("true",env.getProperty("simulator.write-enabled"));});
    }
}
