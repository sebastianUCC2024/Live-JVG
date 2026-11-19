package com.live_jgv.dashboard;
import com.live_jgv.dashboard.infrastructure.jdbc.ScoreProjection;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real application wiring with JDBC doubles; no database, broker or HTTP server. */
class PassiveStartupConfigurationTest {
    @Test void passiveProfileLoadsDurableProjectionWithoutWritesMigrationsAdminOrListenerStartup() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap("SELECT * FROM dashboard.matches WHERE match_id=?","passive-match"))
                .thenReturn(Map.of("home_team_id","home","away_team_id","away","home_name","Home","away_name","Away"));
        var source=mock(DataSource.class);
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context->context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withPropertyValues("spring.profiles.active=passive","live.match-id=passive-match",
                        "live.home-name=Home","live.away-name=Away",
                        "SIMULATOR_WRITE_ENABLED=true","DASHBOARD_RABBIT_LISTENER_ENABLED=true",
                        "DASHBOARD_CATALOG_BOOTSTRAP_ENABLED=true")
                .withBean(DataSource.class,()->source)
                .withBean(JdbcTemplate.class,()->jdbc)
                .withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
                .withUserConfiguration(DashboardBackendApplication.class)
                .run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(ScoreProjection.class);
                    for(String property:new String[]{"dashboard.catalog.bootstrap-enabled","simulator.write-enabled",
                            "spring.flyway.enabled","spring.rabbitmq.dynamic",
                            "spring.rabbitmq.listener.simple.auto-startup","spring.rabbitmq.listener.direct.auto-startup"})
                        assertThat(context.getEnvironment().getProperty(property,Boolean.class)).as(property).isFalse();
                    assertThat(context.getEnvironment().getProperty("spring.sql.init.mode")).isEqualTo("never");
                    assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
                    assertThat(context.getBeansOfType(RabbitAdmin.class)).isEmpty();
                    var listener=context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainer("durableScores");
                    assertThat(listener).isNotNull();
                    assertThat(listener.isAutoStartup()).isFalse();
                    assertThat(listener.isRunning()).isFalse();
                    verify(jdbc).afterPropertiesSet();
                    verify(jdbc).queryForMap("SELECT * FROM dashboard.matches WHERE match_id=?","passive-match");
                    verifyNoMoreInteractions(jdbc);
                    // Actuator may inspect wrapper types; it must not open a connection.
                    assertThat(mockingDetails(source).getInvocations())
                            .noneMatch(call->call.getMethod().getName().equals("getConnection"));
                });
    }
}
