package com.live_jgv.worker.infrastructure;

import com.live_jgv.worker.application.*;
import com.live_jgv.worker.domain.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;

@Configuration(proxyBeanMethods=false)
public class WorkerConfiguration {
    @Bean CommandDecoder commandDecoder(){return new JsonCommandDecoder();}
    @Bean Clock workerClock(){return Clock.systemUTC();}
    @Bean @ConditionalOnMissingBean(SuspensionEvidencePort.class)
    SuspensionEvidencePort unavailableSuspensionEvidence(){return MarketEvidence::unavailable;}
    @Bean AcceptancePolicy acceptancePolicy(@Value("${worker.acceptance.enabled:false}") boolean enabled,
            @Value("${worker.evidence.max-age:5s}") Duration age,@Value("${worker.command.max-age:5m}") Duration commandAge){
        return new AcceptancePolicy(enabled,age,commandAge);
    }
    @Bean AtomicAuthorizationPort atomicAuthorization(JdbcTemplate jdbc,@Value("${worker.authorization.mode:disabled}") String mode,
            @Value("${worker.authorization.expected-database:}") String database,@Value("${worker.authorization.expected-epoch:}") String epoch){
        return switch(mode){case "disabled" -> AtomicAuthorizationPort.disabled();case "postgres-atomic" -> new JdbcAtomicAuthorization(jdbc,database,epoch);
            default -> throw new IllegalArgumentException("Unsupported authorization mode");};
    }
    @Bean WorkerStore workerStore(JdbcTemplate jdbc,PlatformTransactionManager tx,SuspensionEvidencePort source,AcceptancePolicy policy,Clock clock,AtomicAuthorizationPort atomic){
        var transaction=new TransactionTemplate(tx);transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(5);
        return new JdbcWorkerStore(jdbc,transaction,source,policy,clock,atomic);
    }
    @Bean WorkerService workerService(WorkerStore store,CommandDecoder decoder){return new WorkerService(store,decoder,new com.live_jgv.worker.infrastructure.JsonDomainCommandPreparation());}
}
