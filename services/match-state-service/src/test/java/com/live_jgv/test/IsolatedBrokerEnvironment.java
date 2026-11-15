package com.live_jgv.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

/** No sockets/processes at construction. Every endpoint is mandatory and run-scoped. */
public record IsolatedBrokerEnvironment(String run, String project, String context, String endpoint,
        String kafka, String rabbitHost, int rabbitPort, String rabbitUser, String rabbitPassword,
        String rabbitVhost, String postgres, int postgresPort, boolean outageAllowed) {
    public static IsolatedBrokerEnvironment from(Map<String,String> env) {
        try { return parse(env); }
        catch(org.junit.jupiter.api.extension.ExtensionConfigurationException refused){throw refused;}
        catch(RuntimeException invalid){throw new org.junit.jupiter.api.extension.ExtensionConfigurationException("Invalid isolated broker configuration; values withheld");}
    }
    private static IsolatedBrokerEnvironment parse(Map<String,String> env) {
        String run=required(env,"AUDIT_RUN_ID"), project=required(env,"AUDIT_PROJECT");
        if(!run.matches("[a-f0-9]{12}")||!project.equals("live-jvg-9f-"+run))fail("Invalid isolated run/project");
        String kafka=required(env,"TEST_KAFKA_BOOTSTRAP_SERVERS");
        var uri=URI.create("tcp://"+kafka);
        if(!loopback(uri.getHost())||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null
                ||!Objects.equals(uri.getRawPath(),"")||uri.getFragment()!=null)fail("Invalid test Kafka endpoint");
        port(uri.getPort());
        String host=required(env,"TEST_RABBIT_HOST");if(!loopback(host))fail("Rabbit must be loopback");
        int rabbit=port(Integer.parseInt(required(env,"TEST_RABBIT_PORT")));
        if(rabbit==uri.getPort())fail("Broker ports must differ");
        String user=required(env,"TEST_RABBIT_USERNAME"),vhost=required(env,"TEST_RABBIT_VHOST");
        if(!user.equals("jvg9f_"+run)||!vhost.equals("9f_"+run))fail("Rabbit identity must belong to this run");
        String pg=required(env,"MATCH_STATE_TEST_POSTGRES_CONTAINER");
        if(!pg.equals(project+"-postgres"))fail("PostgreSQL container must belong to this run");
        String db=required(env,"MATCH_STATE_BROKER_DB_URL");
        int pgPort=URI.create(db.substring(5)).getPort();port(pgPort);
        String context=required(env,"AUDIT_DOCKER_CONTEXT"), endpoint=required(env,"AUDIT_DOCKER_ENDPOINT");
        if(!context.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")||!(endpoint.startsWith("npipe:////./pipe/")||endpoint.startsWith("unix:///")))fail("Explicit local Docker context/endpoint required");
        return new IsolatedBrokerEnvironment(run,project,context,
                endpoint,kafka,host,rabbit,user,
                required(env,"TEST_RABBIT_PASSWORD"),vhost,pg,pgPort,"true".equals(env.get("LIVE_JVG_OUTAGE_APPROVED")));
    }
    public CachingConnectionFactory rabbitConnection() {
        var cf=new CachingConnectionFactory(rabbitHost,rabbitPort);
        cf.setUsername(rabbitUser);cf.setPassword(rabbitPassword);cf.setVirtualHost(rabbitVhost);return cf;
    }
    public String[] springArguments() {
        return new String[]{"--spring.kafka.bootstrap-servers="+kafka,"--spring.rabbitmq.host="+rabbitHost,
                "--spring.rabbitmq.port="+rabbitPort,"--spring.rabbitmq.username="+rabbitUser,
                "--spring.rabbitmq.password="+rabbitPassword,"--spring.rabbitmq.virtual-host="+rabbitVhost};
    }
    @FunctionalInterface public interface Commands { String run(List<String> args) throws Exception; }
    public void docker(String action) throws Exception { docker(action,IsolatedBrokerEnvironment::command); }
    public void docker(String action, Commands commands) throws Exception {
        if(!outageAllowed)fail("Explicit outage approval required before Docker operations");
        if(!Set.of("stop","start").contains(action))fail("Unsupported outage operation");
        var mapper=new ObjectMapper();
        String actual=mapper.readValue(commands.run(List.of("docker","context","inspect",context,
                "--format","{{json .Endpoints.docker.Host}}")),String.class);
        if(!endpoint.equals(actual))fail("Docker endpoint changed");
        var labels=mapper.readTree(commands.run(List.of("docker","--context",context,"inspect","--type","container",
                "--format","{{json .Config.Labels}}",postgres)));
        if(!project.equals(labels.path("com.docker.compose.project").asText())
                ||!"postgres".equals(labels.path("com.docker.compose.service").asText())
                ||!run.equals(labels.path("live-jvg.audit.run").asText()))fail("Refusing foreign PostgreSQL container");
        String published=commands.run(List.of("docker","--context",context,"port",postgres,"5432/tcp")).trim();
        if(!published.equals("127.0.0.1:"+postgresPort))fail("PostgreSQL port/container mismatch");
        commands.run(List.of("docker","--context",context,action,postgres));
    }
    private static String command(List<String> args) throws Exception {
        var process=new ProcessBuilder(args).redirectErrorStream(true).start();
        if(!process.waitFor(30,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Docker ownership/operation failed; output withheld");
        return new String(process.getInputStream().readNBytes(32768),java.nio.charset.StandardCharsets.UTF_8).trim();
    }
    static boolean loopback(String host){return host!=null&&Set.of("localhost","127.0.0.1","::1","[::1]").contains(host);}
    static int port(int value){if(value<1024||value>65535||Set.of(5432,5672,9092,55434,5673,19092,18080,18081,15673).contains(value))fail("Reserved/MVP/default port refused");return value;}
    static String required(Map<String,String> env,String key){String value=env.get(key);if(value==null||value.isBlank())fail("Missing "+key);return value;}
    static void fail(String message){throw new org.junit.jupiter.api.extension.ExtensionConfigurationException(message);}
    @Override public String toString(){return "IsolatedBrokerEnvironment[run="+run+", credentials withheld]";}
}
