package com.live_jgv.matchstate.infrastructure.config;
import com.live_jgv.matchstate.infrastructure.rabbit.LiveAlertOutboxDispatcher;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
@Configuration(proxyBeanMethods=false)
@Profile("!memory")
@ConditionalOnProperty(name="match-state.alerts.publication-enabled",havingValue="true")
@EnableScheduling
public class LiveAlertPublicationConfiguration {
    private final LiveAlertOutboxDispatcher dispatcher;
    public LiveAlertPublicationConfiguration(JdbcTemplate jdbc,PlatformTransactionManager manager,RabbitTemplate rabbit,
            @Value("${match-state.alerts.enabled:false}") boolean enabled,StartupPolicy startup,
            @Value("${match-state.alerts.confirm-timeout:5s}") Duration timeout) {
        if(!enabled || !startup.publication()) throw new IllegalArgumentException("Alert publication requires explicit generation and publication approval");
        var factory=rabbit.getConnectionFactory().getPublisherConnectionFactory();if(factory==null)factory=rabbit.getConnectionFactory();
        if(!factory.isPublisherConfirms() || factory.isSimplePublisherConfirms() || !factory.isPublisherReturns()) throw new IllegalArgumentException("Correlated confirms and returns required");
        dispatcher=new LiveAlertOutboxDispatcher(jdbc,new TransactionTemplate(manager),rabbit,timeout);
    }
    @Bean FanoutExchange liveAlertsExchange() { return new FanoutExchange("live_alerts",true,false); }
    @Scheduled(fixedDelayString="${match-state.alerts.retry-delay-ms:1000}")
    public void drain() {
        try { dispatcher.drainOne(); } catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("LiveAlert retained; failureType={}",failure.getClass().getSimpleName()); }
    }
}
