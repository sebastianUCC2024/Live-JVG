package com.live_jgv.notification.integration;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** SQL text inspection only. This is not a PostgreSQL/Flyway execution test. */
class NotificationMigrationInventoryTest {
    @Test void expectedExternalInventoryMatchesAllPreparedMigrations() throws Exception {
        var directory=Path.of(Objects.requireNonNull(getClass().getResource("/db/migration")).toURI());
        try(var files=Files.list(directory)) {
            assertEquals(NotificationMigrationInventory.FILES,files.map(p->p.getFileName().toString())
                    .filter(n->n.matches("V[0-9]+__.*\\.sql")).sorted().toList());
        }
        var tables=new TreeSet<String>();tables.add("flyway_schema_history");
        var pattern=Pattern.compile("(?i)CREATE\\s+TABLE\\s+notification\\.([a-z_]+)");
        for(var file:NotificationMigrationInventory.FILES) {
            var matcher=pattern.matcher(Files.readString(directory.resolve(file)));
            while(matcher.find())assertTrue(tables.add(matcher.group(1)),"Duplicate prepared table");
        }
        assertEquals(NotificationMigrationInventory.TABLES,new ArrayList<>(tables));
        assertEquals(List.of("1","2"),NotificationMigrationInventory.VERSIONS);
    }
}
