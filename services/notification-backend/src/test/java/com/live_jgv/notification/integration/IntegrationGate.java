package com.live_jgv.notification.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.live_jgv.notification.integration.IntegrationSettings.refused;

/** No initializer performs I/O. authorize() is called only by explicitly selected ITs. */
public final class IntegrationGate {
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private IntegrationGate() { }
    public static Path module() { return Path.of(System.getProperty("basedir",".")).toAbsolutePath().normalize(); }
    public static final class AuthorizedRun {
        private final IntegrationSettings settings;
        private final Set<String> actions;
        private AuthorizedRun(IntegrationSettings settings,Set<String> actions) { this.settings=settings; this.actions=Set.copyOf(actions); }
        public IntegrationSettings settings() { return settings; }
        public void require(Set<String> required) { if(!actions.containsAll(required)) throw refused("Fixture actions not approved"); }
    }
    public static AuthorizedRun authorize(Set<String> actions) throws Exception {
        var settings=IntegrationSettings.parse(System.getenv(),Boolean.getBoolean("notification.it.profile.enabled"));
        Path repo=module().getParent().getParent();
        Path scope=repo.resolve(".9f").resolve(settings.run()).toRealPath();
        Path approval=Path.of(settings.approvalFile()).toRealPath();
        if (!scope.startsWith(repo.toRealPath()) || !approval.startsWith(scope)) throw refused("Run-owned approval path required");
        var manifest=JSON.readTree(Files.readString(approval));
        validateApproval(settings,manifest,actions,Instant.now());
        requireHash(manifest,"migration_sha256",hash(Files.readAllBytes(module().resolve("src/main/resources/db/migration/V1__notification_inbox_and_alerts.sql"))));
        requireHash(manifest,"source_bundle_sha256",bundle(List.of("pom.xml","src/main","src/test")));
        requireHash(manifest,"compiled_bundle_sha256",bundle(List.of("target/classes","target/test-classes")));
        requireHash(manifest,"jar_sha256",hash(Files.readAllBytes(module().resolve("target/notification-backend-0.0.1-SNAPSHOT.jar"))));
        verifyDocker(settings,manifest);
        var granted=new HashSet<String>(); manifest.path("actions").forEach(action->granted.add(action.asText()));
        return new AuthorizedRun(settings,granted);
    }
    public static void validateApproval(IntegrationSettings s, JsonNode manifest, Set<String> actions, Instant now) {
        Map<String,String> expected=Map.ofEntries(Map.entry("run_id",s.run()),Map.entry("project",s.project()),
                Map.entry("docker_context",s.context()),Map.entry("docker_endpoint",s.endpoint()),Map.entry("database_url",s.databaseUrl()),
                Map.entry("database_user",s.databaseUser()),Map.entry("rabbit_user",s.rabbitUser()),Map.entry("vhost",s.vhost()),
                Map.entry("exchange",s.exchange()),Map.entry("queue_prefix",s.queuePrefix()),Map.entry("match_prefix",s.match("")),
                Map.entry("postgres_container",s.project()+"-postgres"),Map.entry("database_port",Integer.toString(s.databasePort())),
                Map.entry("rabbit_port",Integer.toString(s.rabbitPort())));
        if (manifest==null || !manifest.isObject()) throw refused("Approval object required");
        expected.forEach((key,value)->{if (!value.equals(manifest.path(key).asText())) throw refused("Approval scope mismatch");});
        if (!manifest.path("approved_by_dev1").isBoolean() || !manifest.path("approved_by_dev1").booleanValue()
                || !manifest.path("approved_by_infrastructure").isBoolean() || !manifest.path("approved_by_infrastructure").booleanValue())
            throw refused("DEV 1 and infrastructure approvals required");
        try { if (!Instant.parse(manifest.path("expires_at").asText()).isAfter(now)) throw refused("Approval expired"); }
        catch (java.time.format.DateTimeParseException invalid) { throw refused("Valid approval expiry required"); }
        if (!manifest.path("actions").isArray()) throw refused("Explicit action list required");
        for(String field:List.of("migration_sha256","source_bundle_sha256","compiled_bundle_sha256","jar_sha256","postgres_container_id","rabbitmq_container_id"))
            if(!manifest.path(field).asText().matches("[a-f0-9]{64}")) throw refused("Complete fingerprints and container IDs required");
        Set<String> granted=new HashSet<>(); manifest.path("actions").forEach(value->granted.add(value.asText()));
        if (!granted.containsAll(actions)) throw refused("Required actions not approved");
    }
    static void requireHash(JsonNode manifest,String name,String actual) {
        if (!actual.equals(manifest.path(name).asText())) throw refused("Artifact/source fingerprint mismatch");
    }
    public static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static String bundle(List<String> paths) throws Exception {
        return IntegrationFingerprints.bundle(module(),paths);
    }
    private static String docker(IntegrationSettings s,String... args) throws Exception {
        var command=new ArrayList<>(List.of("docker","--context",s.context())); command.addAll(List.of(args));
        var process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); throw refused("Docker ownership check timed out"); }
        if (process.exitValue()!=0) throw refused("Docker ownership unavailable");
        return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
    }
    private static void verifyDocker(IntegrationSettings s,JsonNode manifest) throws Exception {
        if (!s.endpoint().equals(JSON.readTree(docker(s,"context","inspect",s.context(),"--format","{{json .Endpoints.docker.Host}}")).asText()))
            throw refused("Docker context endpoint changed");
        for (String service:List.of("postgres","rabbitmq")) {
            String container=s.project()+"-"+service;
            // 9F only fixes PostgreSQL's container name; Rabbit's actual owned name is resolved by labels.
            if (service.equals("rabbitmq")) {
                String names=docker(s,"ps","--filter","label=com.docker.compose.project="+s.project(),"--filter","label=com.docker.compose.service=rabbitmq","--format","{{.Names}}");
                if (names.isBlank() || names.lines().count()!=1) throw refused("Exactly one owned Rabbit container required");
                container=names;
            }
            var labels=JSON.readTree(docker(s,"inspect","--type","container",container,"--format","{{json .Config.Labels}}"));
            if (!s.run().equals(labels.path("live-jvg.audit.run").asText()) || !s.project().equals(labels.path("com.docker.compose.project").asText())
                    || !service.equals(labels.path("com.docker.compose.service").asText()) || !"phase9f".equals(labels.path("live-jvg.audit.purpose").asText()))
                throw refused("Resource ownership mismatch");
            String id=docker(s,"inspect",container,"--format","{{.Id}}");
            if (!id.matches("[a-f0-9]{64}") || !id.equals(manifest.path(service+"_container_id").asText())) throw refused("Approved container identity changed");
            if (!"true".equals(docker(s,"inspect",container,"--format","{{.State.Running}}"))) throw refused("Approved resource not running");
            var ports=JSON.readTree(docker(s,"inspect",container,"--format","{{json .NetworkSettings.Ports}}"));
            var bindings=ports.path(service.equals("postgres") ? "5432/tcp" : "5672/tcp");
            int expected=service.equals("postgres") ? s.databasePort() : s.rabbitPort();
            if (!bindings.isArray() || bindings.size()!=1 || !"127.0.0.1".equals(bindings.get(0).path("HostIp").asText())
                    || expected!=bindings.get(0).path("HostPort").asInt()) throw refused("Endpoint/port ownership mismatch");
            var mounts=JSON.readTree(docker(s,"inspect",container,"--format","{{json .Mounts}}"));
            if (!mounts.isArray() || mounts.isEmpty()) throw refused("Owned persistent volume required");
            for (var mount:mounts) {
                String volume=mount.path("Name").asText();
                if (!"volume".equals(mount.path("Type").asText()) || !volume.startsWith(s.project()+"_")) throw refused("Bind or foreign volume refused");
                ownedLabels(s,JSON.readTree(docker(s,"volume","inspect",volume,"--format","{{json .Labels}}")));
            }
            var networks=JSON.readTree(docker(s,"inspect",container,"--format","{{json .NetworkSettings.Networks}}"));
            if (!networks.isObject() || networks.isEmpty()) throw refused("Owned isolated network required");
            var names=networks.fieldNames();
            while (names.hasNext()) {
                String network=names.next();
                if (!network.equals(s.project()+"_isolated")) throw refused("Foreign network refused");
                ownedLabels(s,JSON.readTree(docker(s,"network","inspect",network,"--format","{{json .Labels}}")));
                if (!"true".equals(docker(s,"network","inspect",network,"--format","{{.Internal}}"))) throw refused("Internal network required");
            }
        }
    }
    private static void ownedLabels(IntegrationSettings s,JsonNode labels) {
        if (!s.run().equals(labels.path("live-jvg.audit.run").asText()) || !s.project().equals(labels.path("com.docker.compose.project").asText())
                || !"phase9f".equals(labels.path("live-jvg.audit.purpose").asText())) throw refused("Volume/network ownership mismatch");
    }
}
