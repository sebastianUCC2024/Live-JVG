package com.live_jgv.test;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.extension.*;

/** Test-only boundary evaluated before Spring can open the durable datasource. */
public final class DurableIntegrationSafety implements ExecutionCondition {
    @Override public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        validate(System.getenv());
        return ConditionEvaluationResult.enabled("Explicit disposable-environment approval verified");
    }

    static void validate(Map<String,String> env) {
        if(!"true".equals(env.get("LIVE_JVG_INTEGRATION_APPROVED"))) {
            throw new ExtensionConfigurationException("Explicit LIVE_JVG_INTEGRATION_APPROVED=true required");
        }
        String run=env.get("AUDIT_RUN_ID");
        if(run==null||!run.matches("[a-f0-9]{12}")||!("live-jvg-9f-"+run).equals(env.get("AUDIT_PROJECT")))
            throw new ExtensionConfigurationException("Explicit isolated 9F run/project required");
        for(String key:env.keySet()) if(key.startsWith("SPRING_DATASOURCE_")||key.startsWith("SPRING_FLYWAY_")||key.equals("SPRING_PROFILES_ACTIVE"))
            throw new ExtensionConfigurationException("Inherited Spring datasource/Flyway/profile overrides refused");
        String variable="DASHBOARD_INTEGRATION_DB_URL";
        try {
            String jdbcUrl=env.get(variable);
            if(jdbcUrl==null||!jdbcUrl.startsWith("jdbc:postgresql://"))throw new IllegalArgumentException();
            var uri=URI.create(jdbcUrl.substring(5));
            if(!java.util.Set.of("localhost","127.0.0.1","[::1]","::1").contains(uri.getHost())
                    ||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                    ||uri.getPath()==null||!uri.getPath().matches("/live_jvg_test_[a-z0-9_]+"))throw new IllegalArgumentException();
            if(!uri.getPath().startsWith("/live_jvg_test_"+run+"_")||uri.getPort()<1024||uri.getPort()>65535
                    ||java.util.Set.of(5432,55434).contains(uri.getPort()))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){
            throw new ExtensionConfigurationException(variable+" must name an approved loopback live_jvg_test_* database without URL credentials/options");
        }
        if(!("jvg9f_"+run).equals(env.get("DASHBOARD_TEST_DB_USERNAME")))
            throw new ExtensionConfigurationException("Test DB user must belong to this run");
        for(String key:java.util.List.of("DASHBOARD_TEST_DB_USERNAME","DASHBOARD_TEST_DB_PASSWORD")) {
            if(env.get(key)==null||env.get(key).isBlank())throw new ExtensionConfigurationException("Missing required variable "+key);
        }
    }
}
