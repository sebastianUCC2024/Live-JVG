package com.live_jgv.notification.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure local guard tests; never authorize(), construct fixtures, invoke Docker or connect. */
class IntegrationIsolationTest {
    static final String RUN="abcdef123456";
    static Map<String,String> environment() {
        var env=new HashMap<String,String>(); String p=IntegrationSettings.PREFIX;
        env.put(p+"APPROVED","true"); env.put(p+"RUN_ID",RUN); env.put(p+"DB_PORT","55449"); env.put(p+"RABBIT_PORT","25672");
        env.put(p+"DB_URL","jdbc:postgresql://127.0.0.1:55449/live_jvg_test_"+RUN+"_notification");
        env.put(p+"DB_USERNAME","jvg9f_"+RUN+"_notification"); env.put(p+"RABBIT_USERNAME","jvg9f_"+RUN+"_notification");
        // Public dummy values used only in pure validation, never as infrastructure credentials.
        env.put(p+"DB_PASSWORD","a".repeat(64)); env.put(p+"RABBIT_PASSWORD","b".repeat(64));
        env.put(p+"RABBIT_VHOST","9f_"+RUN+"_notification"); env.put(p+"EXCHANGE","9f_"+RUN+"_notification_updates");
        env.put(p+"QUEUE_PREFIX","notification.9f_"+RUN+".integration"); env.put(p+"DOCKER_CONTEXT","desktop-linux");
        env.put(p+"DOCKER_ENDPOINT","npipe:////./pipe/dockerDesktopLinuxEngine"); env.put(p+"APPROVAL_FILE","not-opened.json");
        return env;
    }
    @Test void requiresExplicitProfileAndFlagBeforeAnyIo() {
        var env=environment(); assertThrows(IllegalStateException.class,()->IntegrationSettings.parse(env,false));
        env.remove(IntegrationSettings.PREFIX+"APPROVED"); assertThrows(IllegalStateException.class,()->IntegrationSettings.parse(env,true));
    }
    @Test void completeExclusiveConfigurationIsAcceptedWithoutOpeningAnything() {
        var settings=IntegrationSettings.parse(environment(),true);
        assertEquals("live-jvg-9f-"+RUN,settings.project());
        assertFalse(settings.toString().contains("a".repeat(64))); assertTrue(settings.toString().contains("WITHHELD"));
    }
    @Test void everyRequiredSettingIsMandatory() {
        for(String key:environment().keySet()) {
            var env=environment(); env.remove(key);
            assertThrows(IllegalStateException.class,()->IntegrationSettings.parse(env,true),"Missing variable must refuse execution");
        }
    }
    @ParameterizedTest @ValueSource(strings={"NOTIFICATION_STORAGE_ENABLED","NOTIFICATION_DB_URL","SPRING_DATASOURCE_URL","PGHOST","DOCKER_HOST","JAVA_TOOL_OPTIONS"})
    void rejectsInheritedInfrastructureOverrides(String key) {
        var env=environment(); env.put(key,"withheld"); assertThrows(IllegalStateException.class,()->IntegrationSettings.parse(env,true));
    }
    @ParameterizedTest @ValueSource(strings={"5432","55434","5673","19092","18080","8082","0","65536"})
    void refusesProductionDefaultMvpAndInvalidPorts(String port) {
        var env=environment(); env.put(IntegrationSettings.PREFIX+"DB_PORT",port);
        assertThrows(IllegalStateException.class,()->IntegrationSettings.parse(env,true));
    }
    @Test void refusesForeignDatabaseQueueVhostAndKnownCredentials() {
        for(var entry:Map.of("DB_URL","jdbc:postgresql://localhost:55434/live_jvg","QUEUE_PREFIX","mvp-dashboard-scores",
                "RABBIT_VHOST","/","DB_USERNAME","postgres","RABBIT_USERNAME","guest","DB_PASSWORD","password").entrySet()) {
            var env=environment(); env.put(IntegrationSettings.PREFIX+entry.getKey(),entry.getValue());
            assertThrows(IllegalStateException.class,()->IntegrationSettings.parse(env,true));
        }
    }
    static com.fasterxml.jackson.databind.node.ObjectNode approval(IntegrationSettings s) {
        var json=IntegrationGate.JSON.createObjectNode();
        Map.ofEntries(Map.entry("run_id",s.run()),Map.entry("project",s.project()),Map.entry("docker_context",s.context()),
            Map.entry("docker_endpoint",s.endpoint()),Map.entry("database_url",s.databaseUrl()),Map.entry("database_user",s.databaseUser()),
            Map.entry("rabbit_user",s.rabbitUser()),Map.entry("vhost",s.vhost()),Map.entry("exchange",s.exchange()),
            Map.entry("queue_prefix",s.queuePrefix()),Map.entry("match_prefix",s.match("")),Map.entry("postgres_container",s.project()+"-postgres"),
            Map.entry("database_port",Integer.toString(s.databasePort())),Map.entry("rabbit_port",Integer.toString(s.rabbitPort())))
            .forEach(json::put);
        json.put("approved_by_dev1",true); json.put("approved_by_infrastructure",true); json.put("expires_at","2099-01-01T00:00:00Z");
        for(String field:List.of("migration_sha256","source_bundle_sha256","compiled_bundle_sha256","jar_sha256","postgres_container_id","rabbitmq_container_id")) json.put(field,"1".repeat(64));
        json.putArray("actions").add("database-read"); return json;
    }
    @Test void requiresActionSpecificRunBoundUnexpiredDualApproval() {
        var settings=IntegrationSettings.parse(environment(),true); var approval=approval(settings); var now=Instant.parse("2026-11-16T00:00:00Z");
        assertDoesNotThrow(()->IntegrationGate.validateApproval(settings,approval,Set.of("database-read"),now));
        assertThrows(IllegalStateException.class,()->IntegrationGate.validateApproval(settings,approval,Set.of("publish"),now));
        approval.put("run_id","other"); assertThrows(IllegalStateException.class,()->IntegrationGate.validateApproval(settings,approval,Set.of("database-read"),now));
        approval.put("run_id",settings.run()); approval.put("approved_by_dev1",false);
        assertThrows(IllegalStateException.class,()->IntegrationGate.validateApproval(settings,approval,Set.of("database-read"),now));
        approval.put("approved_by_dev1",true); approval.put("expires_at","2020-01-01T00:00:00Z");
        assertThrows(IllegalStateException.class,()->IntegrationGate.validateApproval(settings,approval,Set.of("database-read"),now));
    }
    @Test void externalTestsAreTaggedAndNormalSurefireExcludesThem() throws Exception {
        assertEquals("external-integration",NotificationPostgresIT.class.getAnnotation(org.junit.jupiter.api.Tag.class).value());
        assertEquals("external-integration",NotificationRabbitIT.class.getAnnotation(org.junit.jupiter.api.Tag.class).value());
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var document=factory.newDocumentBuilder().parse(IntegrationGate.module().resolve("pom.xml").toFile());
        var xpath=javax.xml.xpath.XPathFactory.newInstance().newXPath();
        assertEquals("external-integration",xpath.evaluate("/project/build/plugins/plugin[artifactId='maven-surefire-plugin']/configuration/excludedGroups",document));
        assertEquals("0",xpath.evaluate("count(/project/build/plugins/plugin[artifactId='maven-failsafe-plugin'])",document));
        assertEquals("notification-integration",xpath.evaluate("/project/profiles/profile[build/plugins/plugin/artifactId='maven-failsafe-plugin']/id",document));
    }
    @Test void fingerprintMismatchAndIncompleteManifestAreRefusedLocally() {
        var settings=IntegrationSettings.parse(environment(),true); var approval=approval(settings);
        assertDoesNotThrow(()->IntegrationGate.requireHash(approval,"jar_sha256","1".repeat(64)));
        assertThrows(IllegalStateException.class,()->IntegrationGate.requireHash(approval,"jar_sha256","2".repeat(64)));
        approval.remove("compiled_bundle_sha256");
        assertThrows(IllegalStateException.class,()->IntegrationGate.validateApproval(settings,approval,Set.of("database-read"),Instant.parse("2026-11-16T00:00:00Z")));
    }
    @Test void standaloneFingerprintToolUsesStandardSha256WithoutInfrastructure() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",IntegrationFingerprints.hash("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void failureDiagnosticsDoNotExposeCredentialMessagesOrCauses() {
        var failure=IntegrationObserver.sanitized(new java.sql.SQLException("private-credential-value"));
        assertFalse(failure.getMessage().contains("private-credential-value")); assertNull(failure.getCause());
        assertTrue(failure.getMessage().contains("SQLException"));
    }
}
