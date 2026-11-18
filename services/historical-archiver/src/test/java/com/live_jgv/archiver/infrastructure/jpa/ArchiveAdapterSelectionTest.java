package com.live_jgv.archiver.infrastructure.jpa;

import com.live_jgv.archiver.application.ArchiveStore;
import com.live_jgv.archiver.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveAdapterSelectionTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(ArchiveConfiguration.class)
        .withBean(org.springframework.boot.autoconfigure.kafka.KafkaProperties.class,org.springframework.boot.autoconfigure.kafka.KafkaProperties::new)
        .withBean(JdbcTemplate.class,()->mock(JdbcTemplate.class))
        .withBean(PlatformTransactionManager.class,()->mock(JpaTransactionManager.class))
        .withBean(ArchivedEventRepository.class,()->mock(ArchivedEventRepository.class))
        .withBean(InputReceiptRepository.class,()->mock(InputReceiptRepository.class));
    @Test void defaultSelectsExactlyOneJpaStoreAndOneManager() {
        context.run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(ArchiveStore.class).size());
            assertInstanceOf(JpaArchiveStore.class,c.getBean(ArchiveStore.class));assertEquals(1,c.getBeansOfType(PlatformTransactionManager.class).size());});
    }
    @Test void explicitJdbcFallbackIsExclusive() {
        context.withPropertyValues("archive.storage.adapter=jdbc").run(c->{assertNull(c.getStartupFailure());
            assertEquals(1,c.getBeansOfType(ArchiveStore.class).size());assertInstanceOf(JdbcArchiveStore.class,c.getBean(ArchiveStore.class));});
    }
    @Test void unknownAdapterFailsInsteadOfSilentlySwitching() {
        context.withPropertyValues("archive.storage.adapter=unknown").run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void runtimeDefaultsForbidOrmDdlAndOpenSessionInView() throws Exception {
        var properties=new java.util.Properties();try(var in=getClass().getResourceAsStream("/application.properties")){properties.load(in);}
        assertEquals("validate",properties.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false",properties.getProperty("spring.jpa.open-in-view"));assertEquals("false",properties.getProperty("spring.flyway.enabled"));
    }
}
