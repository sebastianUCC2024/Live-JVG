package com.live_jgv.notification.integration;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Whitelisted evidence only: never raw exceptions, environment, passwords or auth headers. */
public final class IntegrationEvidence {
    private static final Set<String> KEYS=Set.of("event_id","alert_id","result","rows","ack","nack","redelivered",
            "ready_messages","dlq_messages","rest_status","rest_items","has_more","elapsed_ms","error_type");
    private final Path path;
    public IntegrationEvidence(IntegrationSettings settings) throws Exception {
        Path root=IntegrationGate.module().resolve("target/integration-evidence");
        Files.createDirectories(root);
        if (!root.toRealPath().startsWith(IntegrationGate.module().toRealPath())) throw IntegrationSettings.refused("Evidence path escaped module");
        Path directory=root.resolve(settings.run()); Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(root.toRealPath())) throw IntegrationSettings.refused("Evidence run path escaped module");
        path=directory.resolve("evidence-"+UUID.randomUUID()+".jsonl");
    }
    public synchronized void record(String scenario,Map<String,?> values) throws Exception {
        if (!KEYS.containsAll(values.keySet()) || !scenario.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Unsafe evidence field");
        var row=new LinkedHashMap<String,Object>(); row.put("scenario",scenario); row.put("recorded_at",Instant.now().toString()); row.putAll(values);
        Files.writeString(path,IntegrationGate.JSON.writeValueAsString(row)+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
}
