package com.live_jgv.archiver.infrastructure.jpa;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveJpaSafetyTest {
    @Test void allowsOnlyValidateNoneOrNoOrmDdlSetting() {
        var guard=new ArchiveJpaSafety(new MockEnvironment());
        for(String value:List.of("validate","none"))assertDoesNotThrow(()->guard.customize(new HashMap<>(Map.of("hibernate.hbm2ddl.auto",value))));
        assertDoesNotThrow(()->guard.customize(new HashMap<>()));
    }
    @Test void rejectsDestructiveHibernateActionsBeforeFactoryCreation() {
        for(String value:List.of("create","create-drop","update","drop")) {
            assertThrows(IllegalStateException.class,()->new ArchiveJpaSafety(new MockEnvironment()).customize(new HashMap<>(Map.of("hibernate.hbm2ddl.auto",value))));
            assertThrows(IllegalStateException.class,()->new ArchiveJpaSafety(new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto",value)).customize(new HashMap<>()));
        }
    }
    @Test void rejectsAlternateSchemaGenerationAndOpenSessionOverrides() {
        for(String key:List.of("jakarta.persistence.schema-generation.database.action","javax.persistence.schema-generation.database.action",
                "jakarta.persistence.schema-generation.scripts.action","javax.persistence.schema-generation.scripts.action"))
            assertThrows(IllegalStateException.class,()->new ArchiveJpaSafety(new MockEnvironment()).customize(new HashMap<>(Map.of(key,"create"))));
        for(String key:List.of("spring.jpa.generate-ddl","spring.jpa.open-in-view"))
            assertThrows(IllegalStateException.class,()->new ArchiveJpaSafety(new MockEnvironment().withProperty(key,"true")).customize(new HashMap<>()));
    }
}
