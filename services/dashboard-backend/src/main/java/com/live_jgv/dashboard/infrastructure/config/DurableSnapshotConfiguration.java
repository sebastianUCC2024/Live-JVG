package com.live_jgv.dashboard.infrastructure.config;
import com.live_jgv.dashboard.application.port.out.DashboardQueries;

import com.live_jgv.dashboard.application.port.in.GetMatchSnapshot;
import org.springframework.context.annotation.*;

/** Both REST snapshot recovery and STOMP recovery read the committed inbox. */
@Configuration(proxyBeanMethods = false)
@Profile("!memory")
public class DurableSnapshotConfiguration {
    @Bean
    GetMatchSnapshot durableMatchSnapshot(DashboardQueries projection) {
        return projection::snapshot;
    }
}
