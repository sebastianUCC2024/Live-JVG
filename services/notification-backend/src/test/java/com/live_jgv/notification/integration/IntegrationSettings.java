package com.live_jgv.notification.integration;

import java.util.*;

/** Pure validation. Construction never opens files, sockets or database connections. */
public record IntegrationSettings(String run, String context, String endpoint, int databasePort, int rabbitPort,
        String databaseUrl, String databaseUser, String databasePassword, String rabbitUser, String rabbitPassword,
        String vhost, String exchange, String queuePrefix, String approvalFile) {
    public static final String PREFIX="JVG_NOTIFICATION_IT_";
    private static String required(Map<String,String> env,String name) {
        String value=env.get(PREFIX+name);
        if (value==null || value.isBlank()) throw refused("Missing "+PREFIX+name);
        return value;
    }
    public static IntegrationSettings parse(Map<String,String> env, boolean profile) {
        if (!profile || !"true".equals(env.get(PREFIX+"APPROVED"))) throw refused("Explicit profile and approval flag required");
        for (String key:env.keySet()) {
            if (key.startsWith("NOTIFICATION_") || key.startsWith("SPRING_") || key.startsWith("DB_")
                    || key.startsWith("RABBITMQ_") || key.startsWith("PG") || key.startsWith("DOCKER_")
                    || Set.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS").contains(key))
                throw refused("Inherited infrastructure override refused; values withheld");
        }
        String run=required(env,"RUN_ID");
        if (!run.matches("[a-f0-9]{12}")) throw refused("Exclusive 12-hex run ID required");
        int db=port(required(env,"DB_PORT")), rabbit=port(required(env,"RABBIT_PORT"));
        if (db==rabbit) throw refused("Ports must be distinct");
        String database="live_jvg_test_"+run+"_notification";
        String url="jdbc:postgresql://127.0.0.1:"+db+"/"+database;
        String user="jvg9f_"+run+"_notification";
        String vhost="9f_"+run+"_notification", exchange=vhost+"_updates", queue="notification.9f_"+run+".integration";
        if (!url.equals(required(env,"DB_URL")) || !user.equals(required(env,"DB_USERNAME"))
                || !user.equals(required(env,"RABBIT_USERNAME")) || !vhost.equals(required(env,"RABBIT_VHOST"))
                || !exchange.equals(required(env,"EXCHANGE")) || !queue.equals(required(env,"QUEUE_PREFIX")))
            throw refused("Cross-run or nonexclusive destination refused");
        String context=required(env,"DOCKER_CONTEXT"), endpoint=required(env,"DOCKER_ENDPOINT");
        if (!context.matches("[A-Za-z0-9][A-Za-z0-9_.-]*") || !(endpoint.startsWith("npipe:////./pipe/") || endpoint.startsWith("unix:///")))
            throw refused("Explicit local Docker endpoint required");
        String dbPassword=secret(required(env,"DB_PASSWORD")), rabbitPassword=secret(required(env,"RABBIT_PASSWORD"));
        if (dbPassword.equals(rabbitPassword)) throw refused("Independent test passwords required");
        return new IntegrationSettings(run,context,endpoint,db,rabbit,url,user,dbPassword,user,rabbitPassword,vhost,exchange,queue,required(env,"APPROVAL_FILE"));
    }
    private static int port(String value) {
        try {
            int port=Integer.parseInt(value);
            if (port<1024 || port>65535 || Set.of(5432,55434,5672,5673,15672,15673,9092,19092,18080,18081,8080,8081,8082).contains(port)) throw new IllegalArgumentException();
            return port;
        } catch (RuntimeException invalid) { throw refused("Alternate isolated port required"); }
    }
    private static String secret(String value) {
        if (value.length()<32 || value.isBlank() || value.toLowerCase(Locale.ROOT).matches(".*(password|changeme|placeholder|generate|secret|guest).*"))
            throw refused("Private authorized test credentials required");
        return value;
    }
    public String databaseName() { return "live_jvg_test_"+run+"_notification"; }
    public String project() { return "live-jvg-9f-"+run; }
    public String match(String suffix) { return "9f_"+run+"_notification_"+suffix; }
    @Override public String toString() { return "IntegrationSettings[run="+run+", credentials=WITHHELD]"; }
    public static IllegalStateException refused(String reason) { return new IllegalStateException("External notification tests refused: "+reason); }
}
