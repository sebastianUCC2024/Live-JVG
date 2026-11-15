package com.live_jgv.dashboard;

import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;

/** Checks execution boundaries, not the availability of external services. */
class ValidationPipelineTest {
    private final Path root=Path.of("..","..");
    @Test @SuppressWarnings("unchecked")
    void ciBuildsAllThreeRealServicesAndBothClientsWithoutDeployOrExternalInfrastructure() throws Exception {
        var workflow=(Map<String,Object>)new Yaml().load(Files.readString(root.resolve(".github/workflows/validation.yml")));
        assertEquals(Map.of("contents","read"),workflow.get("permissions"));
        var jobs=(Map<String,Map<String,Object>>)workflow.get("jobs");
        var java=jobs.get("java");
        var matrix=(Map<String,Object>)((Map<String,Object>)java.get("strategy")).get("matrix");
        assertEquals(Set.of("live-feed-producer","match-state-service","dashboard-backend"),new HashSet<>((List<String>)matrix.get("service")));
        var javaSteps=(List<Map<String,Object>>)java.get("steps");
        assertTrue(javaSteps.stream().anyMatch(step->String.valueOf(step.get("run")).equals("mvn -B -ntp -f services/${{ matrix.service }}/pom.xml verify")));
        for(String name:List.of("react","node"))assertNotNull(jobs.get(name));
        for(var job:jobs.values()) {
            assertFalse(job.containsKey("services"));
            for(var step:(List<Map<String,Object>>)job.get("steps")) {
                String command=String.valueOf(step.get("run"));
                assertFalse(command.contains("skipTests"));assertFalse(command.contains("docker"));
                assertFalse(command.contains("deploy"));assertFalse(command.contains("integration-postgres"));
            }
        }
    }
    @Test void rootAggregationIsExplicitAndExternalTestClassesCannotEnterTheDefaultSuites() throws Exception {
        var parser=DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var rootPom=parser.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        var modules=rootPom.getElementsByTagName("module");assertEquals(3,modules.getLength());
        for(String service:List.of("match-state-service","dashboard-backend")) {
            Path base=root.resolve("services/"+service);
            var pom=parser.newDocumentBuilder().parse(base.resolve("pom.xml").toFile());
            assertEquals("external-integration",pom.getElementsByTagName("audit.excludedTags").item(0).getTextContent());
            assertEquals("${audit.excludedTags}",pom.getElementsByTagName("excludedGroups").item(0).getTextContent());
            try(var paths=Files.walk(base.resolve("src/test/java"))) {
                for(var file:paths.filter(p->p.toString().endsWith("IntegrationTest.java")).toList()) {
                    String source=Files.readString(file);
                    if(source.contains("DriverManagerDataSource")||source.contains("@SpringBootTest(properties={")||source.contains("ProcessBuilder(\"docker\"")) {
                        assertTrue(source.contains("Tag(\"external-integration\")"),file.toString());
                        assertTrue(source.contains("ExtendWith(com.live_jgv.test.DurableIntegrationSafety.class)"),file.toString());
                    }
                }
            }
        }
    }
}
