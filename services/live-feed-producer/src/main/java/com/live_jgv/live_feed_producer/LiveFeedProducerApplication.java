package com.live_jgv.live_feed_producer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.live_jgv.live_feed_producer.config.SimulatorMatchCatalog;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SimulatorMatchCatalog.class)
public class LiveFeedProducerApplication {

	public static void main(String[] args) {
		SpringApplication.run(LiveFeedProducerApplication.class, args);
	}

}
