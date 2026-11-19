package com.live_jgv.betting.infrastructure;

import org.springframework.boot.actuate.health.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Consulta los pendientes; su cantidad no demuestra disponibilidad del broker. */
@Component("marketOutbox")
public final class MarketOutboxHealth implements HealthIndicator {
    private final JdbcTemplate jdbc;private final boolean publicationEnabled;
    public MarketOutboxHealth(JdbcTemplate jdbc,@Value("${betting.publication.enabled:false}") boolean enabled){
        this.jdbc=jdbc;this.publicationEnabled=enabled;
    }
    @Override public Health health(){
        try {
            var row=jdbc.queryForMap("""
                    SELECT count(*) AS pending,
                        coalesce(greatest(extract(epoch FROM clock_timestamp()-min(created_at)),0),0) AS oldest_age_seconds
                    FROM betting_control.suspension_outbox WHERE published_at IS NULL
                    """);
            long pending=((Number)row.get("pending")).longValue();
            double age=((Number)row.get("oldest_age_seconds")).doubleValue();
            if(pending<0||!Double.isFinite(age)||age<0)throw new IllegalStateException("Invalid backlog summary");
            return Health.up().withDetail("pending",pending).withDetail("oldest_age_seconds",age)
                    .withDetail("publication_enabled",publicationEnabled).withDetail("broker_verified",false).build();
        } catch(RuntimeException unavailable){
            return Health.down().withDetail("state","BACKLOG_UNAVAILABLE").build();
        }
    }
}
