package com.live_jgv.live_feed_producer.entrypoint.rest;

import com.live_jgv.live_feed_producer.application.service.MatchSimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IntegrationRestExposureTest {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withBean(MatchSimulationService.class, () -> mock(MatchSimulationService.class))
            .withUserConfiguration(MatchSimulationController.class);

    @Test
    void disabledByDefaultEvenInDevelopment() {
        context.withPropertyValues("spring.profiles.active=dev")
                .run(app -> assertThat(app).doesNotHaveBean(MatchSimulationController.class));
    }

    @Test
    void explicitFlagAloneDoesNotEnableProductionEndpoint() {
        context.withPropertyValues("spring.profiles.active=prod", "live-feed.integration-rest.enabled=true")
                .run(app -> assertThat(app).doesNotHaveBean(MatchSimulationController.class));
    }

    @Test
    void explicitDevelopmentConfigurationEnablesEndpoint() {
        context.withPropertyValues("spring.profiles.active=dev", "live-feed.integration-rest.enabled=true")
                .run(app -> assertThat(app).hasSingleBean(MatchSimulationController.class));
    }
}
