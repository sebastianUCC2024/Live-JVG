package com.live_jgv.archiver.infrastructure.jpa;

import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

/** Runs before EntityManagerFactory creation: Flyway remains the only schema owner. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class ArchiveJpaSafety implements HibernatePropertiesCustomizer {
    private final Environment environment;
    public ArchiveJpaSafety(Environment environment) { this.environment=environment; }
    @Override public void customize(Map<String,Object> properties) {
        for(String name:Set.of("spring.jpa.hibernate.ddl-auto","spring.jpa.properties.hibernate.hbm2ddl.auto"))
            requireValidationOnly(environment.getProperty(name));
        requireValidationOnly(properties.get("hibernate.hbm2ddl.auto"));
        for(String name:Set.of("jakarta.persistence.schema-generation.database.action","javax.persistence.schema-generation.database.action",
                "jakarta.persistence.schema-generation.scripts.action","javax.persistence.schema-generation.scripts.action")) {
            Object value=properties.get(name);
            if(value!=null && !"none".equals(value)) throw new IllegalStateException("Archive forbids ORM schema generation");
        }
        if(environment.getProperty("spring.jpa.generate-ddl",Boolean.class,false)
                ||environment.getProperty("spring.jpa.open-in-view",Boolean.class,false))
            throw new IllegalStateException("Archive requires schema generation and Open Session in View disabled");
    }
    private static void requireValidationOnly(Object value) {
        if(value!=null && !Set.of("validate","none").contains(value))
            throw new IllegalStateException("Archive Hibernate DDL must be validate or none");
    }
}
