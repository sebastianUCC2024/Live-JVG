package com.live_jgv.test;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.extension.*;

/** Evaluated before any Spring context, migration, socket or Docker operation. */
public final class DurableIntegrationSafety implements ExecutionCondition {
    @Override public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        validate(System.getenv(), context.getRequiredTestClass().getSimpleName());
        return ConditionEvaluationResult.enabled("Explicit disposable-environment approval verified");
    }

    static void validate(Map<String,String> env, String testClass) {
        require(env,"LIVE_JVG_INTEGRATION_APPROVED","true");
        String run=required(env,"AUDIT_RUN_ID");
        if(!run.matches("[a-f0-9]{12}")||!("live-jvg-9f-"+run).equals(env.get("AUDIT_PROJECT")))
            throw new ExtensionConfigurationException("Explicit isolated 9F run/project required");
        for(String key:env.keySet()) if(key.startsWith("SPRING_DATASOURCE_")||key.startsWith("SPRING_FLYWAY_")||key.equals("SPRING_PROFILES_ACTIVE"))
            throw new ExtensionConfigurationException("Inherited Spring datasource/Flyway/profile overrides refused");
        String database = switch (testClass) {
            case "DurableMessagingIntegrationTest" -> "MATCH_STATE_BROKER_DB_URL";
            case "QuarantineMessagingIntegrationTest" -> "MATCH_STATE_QUARANTINE_DB_URL";
            case "ConcurrentRabbitOutboxIntegrationTest" -> "MATCH_STATE_BROKER_DB_URL";
            default -> "MATCH_STATE_TEST_DB_URL";
        };
        validateDatabase(env.get(database), database);
        var databaseUri=URI.create(env.get(database).substring(5));
        IsolatedBrokerEnvironment.port(databaseUri.getPort());
        if(!databaseUri.getPath().startsWith("/live_jvg_test_"+run+"_")
                ||!("jvg9f_"+run).equals(env.get("MATCH_STATE_TEST_DB_USERNAME")))
            throw new ExtensionConfigurationException("Database and user must belong to this run");
        required(env,"MATCH_STATE_TEST_DB_USERNAME"); required(env,"MATCH_STATE_TEST_DB_PASSWORD");
        if(testClass.equals("ConcurrentRabbitOutboxIntegrationTest"))IsolatedBrokerEnvironment.from(env);
        if (testClass.equals("DurableMessagingIntegrationTest") || testClass.equals("QuarantineMessagingIntegrationTest")) {
            require(env,"LIVE_JVG_OUTAGE_APPROVED","true");
            boolean quarantine=testClass.equals("QuarantineMessagingIntegrationTest");
            require(env,quarantine?"MATCH_STATE_TEST_QUARANTINE":"MATCH_STATE_TEST_BROKERS","true");
            var brokers=IsolatedBrokerEnvironment.from(env);
            if(brokers.postgresPort()!=databaseUri.getPort())throw new ExtensionConfigurationException("Outage database/container port mismatch");
        }
    }

    static void validateDatabase(String jdbcUrl, String variable) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            var uri=URI.create(jdbcUrl.substring(5));
            if (!java.util.Set.of("localhost","127.0.0.1","[::1]","::1").contains(uri.getHost())
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || uri.getPath()==null || !uri.getPath().matches("/live_jvg_test_[a-z0-9_]+")) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            // Never include the URL, which may contain credentials, in exception messages.
            throw new ExtensionConfigurationException(variable+" must name an approved loopback live_jvg_test_* database without URL credentials/options");
        }
    }

    private static String required(Map<String,String> env,String key) {
        String value=env.get(key);
        if(value==null||value.isBlank())throw new ExtensionConfigurationException("Missing required variable "+key);
        return value;
    }
    private static void require(Map<String,String> env,String key,String expected) {
        if(!expected.equals(env.get(key)))throw new ExtensionConfigurationException("Explicit "+key+"="+expected+" required");
    }
}
