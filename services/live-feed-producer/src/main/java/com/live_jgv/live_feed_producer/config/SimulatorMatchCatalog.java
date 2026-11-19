package com.live_jgv.live_feed_producer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds deployment properties to the technology-independent application catalog. */
@ConfigurationProperties(prefix = "simulator")
public class SimulatorMatchCatalog extends com.live_jgv.live_feed_producer.application.model.SimulatorMatchCatalog {
}
