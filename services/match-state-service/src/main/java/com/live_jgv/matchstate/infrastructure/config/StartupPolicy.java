package com.live_jgv.matchstate.infrastructure.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Independent startup gates; all defaults retain the active startup behavior. */
public record StartupPolicy(boolean catalog, boolean recovery, boolean publication) {
    public static StartupPolicy from(Environment env) {
        var policy = new StartupPolicy(
                env.getProperty("match-state.catalog.bootstrap-enabled", Boolean.class, true),
                env.getProperty("match-state.recovery.enabled", Boolean.class, true),
                env.getProperty("match-state.publication.enabled", Boolean.class, true));
        boolean listener = env.getProperty("spring.kafka.listener.auto-startup", Boolean.class, true);
        boolean durable = !env.acceptsProfiles(Profiles.of("memory"));
        if (policy.recovery && !policy.publication && durable)
            throw new IllegalStateException("Durable recovery requires publication enabled");
        if (listener && (!policy.publication || (durable && !policy.recovery)))
            throw new IllegalStateException("Kafka startup requires publication and durable recovery enabled");
        return policy;
    }
}
