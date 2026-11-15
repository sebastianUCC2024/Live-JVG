package com.live_jgv.dashboard.infrastructure.web;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dashboard.web")
public record DashboardWebProperties(List<String> allowedOrigins) {
    public DashboardWebProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("At least one explicit browser origin is required");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
        for (var origin : allowedOrigins) {
            var uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException("Browser origins must be explicit HTTP(S) origins");
            }
        }
    }
}
