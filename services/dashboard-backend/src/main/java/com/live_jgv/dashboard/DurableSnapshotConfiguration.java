package com.live_jgv.dashboard;

import com.live_jgv.dashboard.domain.port.in.GetMatchSnapshot;
import org.springframework.context.annotation.*;

/** Both REST snapshot recovery and STOMP recovery read the committed inbox. */
@Configuration(proxyBeanMethods = false)
@Profile("!memory")
public class DurableSnapshotConfiguration {
    @Bean
    GetMatchSnapshot durableMatchSnapshot(ScoreProjection projection) {
        return projection::snapshot;
    }
}
