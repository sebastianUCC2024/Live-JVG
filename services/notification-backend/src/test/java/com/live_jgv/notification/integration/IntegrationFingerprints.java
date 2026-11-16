package com.live_jgv.notification.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Standalone JDK 17 source-file tool: reads local files only, no credentials or authorization creation. */
public final class IntegrationFingerprints {
    public static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static String bundle(Path module,List<String> paths) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256"); var files=new TreeSet<Path>();
        for(String relative:paths) {
            Path root=module.resolve(relative);
            if(!Files.exists(root)) throw new IllegalStateException("Fingerprint input missing");
            if(Files.isDirectory(root)) try(var walk=Files.walk(root)) { walk.filter(Files::isRegularFile).forEach(files::add); }
            else files.add(root);
        }
        for(Path file:files) {
            if(!file.toRealPath().startsWith(module.toRealPath())) throw new IllegalStateException("Fingerprint input escaped module");
            digest.update(module.relativize(file).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0); digest.update(Files.readAllBytes(file)); digest.update((byte)0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Pass notification-backend module directory");
        Path module=Path.of(args[0]).toAbsolutePath().normalize();
        if(!module.endsWith(Path.of("services","notification-backend"))) throw new IllegalArgumentException("Notification module required");
        System.out.println("migration_sha256="+hash(Files.readAllBytes(module.resolve("src/main/resources/db/migration/V1__notification_inbox_and_alerts.sql"))));
        System.out.println("source_bundle_sha256="+bundle(module,List.of("pom.xml","src/main","src/test")));
        System.out.println("compiled_bundle_sha256="+bundle(module,List.of("target/classes","target/test-classes")));
        System.out.println("jar_sha256="+hash(Files.readAllBytes(module.resolve("target/notification-backend-0.0.1-SNAPSHOT.jar"))));
    }
}
