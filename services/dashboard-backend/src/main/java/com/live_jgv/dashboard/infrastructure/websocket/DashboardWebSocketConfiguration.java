package com.live_jgv.dashboard.infrastructure.websocket;

import com.live_jgv.dashboard.infrastructure.web.DashboardWebProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.socket.config.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(DashboardWebProperties.class)
public class DashboardWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final DashboardWebProperties properties;
    private final ThreadPoolTaskScheduler scheduler;

    public DashboardWebSocketConfiguration(DashboardWebProperties properties) {
        this.properties = properties;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setThreadNamePrefix("dashboard-stomp-heartbeat-");
        this.scheduler.setPoolSize(1);
    }

    @Bean
    public ThreadPoolTaskScheduler dashboardHeartbeatScheduler() {
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws").setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic").setTaskScheduler(scheduler).setHeartbeatValue(new long[] {10000, 10000});
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompInboundInterceptor());
    }

    @Bean
    public WebMvcConfigurer dashboardCorsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**").allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                        .allowedMethods("GET").allowedHeaders("Accept", "Content-Type").maxAge(3600);
            }
        };
    }
}
