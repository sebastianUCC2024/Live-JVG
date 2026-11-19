package com.live_jgv.live_feed_producer.infrastructure.config;

import com.live_jgv.live_feed_producer.application.port.out.MatchEventPublisher;
import com.live_jgv.live_feed_producer.application.service.MatchSimulationService;
import com.live_jgv.live_feed_producer.config.SimulatorMatchCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composes the simulation use case without making application code depend on Spring. */
@Configuration(proxyBeanMethods = false)
public class SimulationConfiguration {
    @Bean
    MatchSimulationService matchSimulationService(MatchEventPublisher publisher, SimulatorMatchCatalog catalog) {
        return new MatchSimulationService(publisher, catalog);
    }
}
