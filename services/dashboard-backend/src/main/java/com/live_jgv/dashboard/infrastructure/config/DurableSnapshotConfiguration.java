package com.live_jgv.dashboard.infrastructure.config;
import com.live_jgv.dashboard.application.port.out.DashboardQueries;

import com.live_jgv.dashboard.application.port.in.GetMatchSnapshot;
import org.springframework.context.annotation.*;

/** REST y STOMP recuperan el estado desde el inbox confirmado. */
@Configuration(proxyBeanMethods = false)
@Profile("!memory")
public class DurableSnapshotConfiguration {
    @Bean
    GetMatchSnapshot durableMatchSnapshot(DashboardQueries projection) {
        return projection::snapshot;
    }
}
