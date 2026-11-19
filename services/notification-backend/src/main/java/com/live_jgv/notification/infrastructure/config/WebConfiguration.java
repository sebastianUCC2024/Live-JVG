package com.live_jgv.notification.infrastructure.config;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods=false)
public class WebConfiguration implements WebMvcConfigurer {
    private final String[] origins;
    public WebConfiguration(@Value("${notification.web.allowed-origins:}") String origins) {
        this.origins=Arrays.stream(origins.split(",")).map(String::trim).filter(value->!value.isEmpty()).toArray(String[]::new);
        for (String value : this.origins) {
            var uri=URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || (uri.getPath()!=null && !uri.getPath().isEmpty()))
                throw new IllegalArgumentException("Exact HTTP(S) origins required");
        }
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        if (origins.length>0) registry.addMapping("/api/alerts").allowedOrigins(origins).allowedMethods("GET").allowedHeaders("Accept");
    }
}
