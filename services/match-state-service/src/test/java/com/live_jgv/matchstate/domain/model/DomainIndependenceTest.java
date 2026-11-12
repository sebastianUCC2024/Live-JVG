package com.live_jgv.matchstate.domain.model;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DomainIndependenceTest {
    @Test
    void domainAndApplicationImportOnlyJdkAndInnerLayers() throws Exception {
        var root = Path.of("src/main/java/com/live_jgv/matchstate");
        // Scan every source, so adding a future domain class cannot bypass the rule.
        for (String layer : List.of("domain", "application")) {
            try (var paths = Files.walk(root.resolve(layer))) {
                for (var source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                    for (String line : Files.readAllLines(source)) {
                        if (line.strip().startsWith("import ")) {
                            assertTrue(line.startsWith("import java.") || line.startsWith("import com.live_jgv.matchstate.domain."),
                                    source + " has forbidden dependency: " + line);
                        }
                    }
                }
            }
        }
    }
}
