package com.live_jgv.worker.infrastructure;

import com.live_jgv.worker.application.AtomicAuthorizationPort;
import com.live_jgv.worker.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

/** No second datasource, REST or autonomous transaction: source locks remain owned by worker commit. */
public final class JdbcAtomicAuthorization implements AtomicAuthorizationPort {
    private final JdbcTemplate jdbc;private final String database;private final UUID epoch;
    public JdbcAtomicAuthorization(JdbcTemplate jdbc,String database,String epoch){
        if(database==null||database.isBlank())throw new IllegalArgumentException("Approved common database required");
        this.jdbc=jdbc;this.database=database;this.epoch=UUID.fromString(epoch);
    }
    @Override public void verifyInstallation(){
        var info=jdbc.queryForMap("""
            SELECT current_database() AS database_name,
                has_function_privilege(current_user,'betting_control.lock_market_authority(text)','EXECUTE') AS can_lock,
                has_function_privilege(current_user,'betting_worker.record_atomic_authorization(text,text,bigint,uuid,uuid)','EXECUTE') AS can_record,
                (EXISTS (SELECT 1 FROM (VALUES ('betting_control.market_state'),('betting_control.pending_review'),
                    ('betting_control.authorization_lease'),('betting_control.authorization_authority')) AS source(relation)
                    WHERE has_any_column_privilege(current_user,source.relation,'INSERT')
                        OR has_any_column_privilege(current_user,source.relation,'UPDATE')
                        OR has_table_privilege(current_user,source.relation,'DELETE')
                        OR has_table_privilege(current_user,source.relation,'TRUNCATE'))
                    OR has_schema_privilege(current_user,'betting_control','CREATE')
                    OR has_schema_privilege(current_user,'betting_worker','CREATE')) AS can_change_source,
                (has_any_column_privilege(current_user,'betting_worker.operation_authorization','INSERT')
                    OR has_any_column_privilege(current_user,'betting_worker.operation_authorization','UPDATE')
                    OR has_table_privilege(current_user,'betting_worker.operation_authorization','DELETE')
                    OR has_table_privilege(current_user,'betting_worker.operation_authorization','TRUNCATE')) AS can_forge_grant
            """);
        if(!database.equals(info.get("database_name"))||!Boolean.TRUE.equals(info.get("can_lock"))||!Boolean.TRUE.equals(info.get("can_record"))
                ||Boolean.TRUE.equals(info.get("can_change_source"))||Boolean.TRUE.equals(info.get("can_forge_grant")))
            throw new IllegalStateException("Atomic authority database or role permissions are not isolated");
    }
    private Object transaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Writable common transaction required");
        var resource=TransactionSynchronizationManager.getResource(Objects.requireNonNull(jdbc.getDataSource()));
        if(resource==null)throw new IllegalStateException("Worker datasource is not bound to this transaction");return resource;
    }
    @Override public Session lock(SimulatedCommand command){
        Object bound=transaction();
        var row=jdbc.queryForMap("SELECT * FROM betting_control.lock_market_authority(?::text)",command.matchId());
        if(!database.equals(row.get("database_name"))||!epoch.toString().equals(row.get("authority_epoch").toString()))
            throw new IllegalStateException("Unexpected authority identity");
        String refusal=null;
        long revision=((Number)row.get("market_version")).longValue();
        Instant issued=instant(row.get("issued_at")),until=instant(row.get("valid_until")),checked=instant(row.get("checked_at"));
        if(!"OPEN".equals(row.get("status")))refusal="MARKET_"+row.get("status");
        else if(Boolean.TRUE.equals(row.get("inconsistent"))||((Number)row.get("pending_reviews")).longValue()!=0)refusal="MARKET_UNRESOLVED";
        else if(row.get("lease_id")==null||row.get("lease_version")==null||((Number)row.get("lease_version")).longValue()!=revision
                ||issued==null||until==null||checked==null||issued.isAfter(checked)||!until.isAfter(checked))refusal="STALE_AUTHORITY_LEASE";
        final String denied=refusal;
        final MarketEvidence proof=denied==null?new MarketEvidence(command.matchId(),"postgres-atomic:"+database,epoch.toString(),revision,
                MarketEvidence.Status.OPEN,issued,until):MarketEvidence.unavailable(command.matchId());
        final String lease=row.get("lease_id")==null?null:row.get("lease_id").toString();
        return new Session(){
            public boolean authoritative(){return true;}
            public MarketEvidence evidence(){return proof;}
            public String refusal(){return denied;}
            public Grant recordGrant(String canonical){
                if(transaction()!=bound)throw new IllegalStateException("Authority session escaped its original transaction");
                if(denied!=null)return new Grant(false,denied);
                Boolean granted=jdbc.queryForObject("SELECT betting_worker.record_atomic_authorization(?,?,?,?::uuid,?::uuid)",Boolean.class,
                        command.operationId(),canonical,revision,epoch.toString(),lease);
                return new Grant(Boolean.TRUE.equals(granted),Boolean.TRUE.equals(granted)?"ATOMIC_POSTGRES_AUTHORIZED":"AUTHORITY_CHANGED_OR_EXPIRED");
            }
        };
    }
    private static Instant instant(Object value){
        if(value==null)return null;if(value instanceof Timestamp t)return t.toInstant();
        if(value instanceof OffsetDateTime t)return t.toInstant();if(value instanceof Instant t)return t;
        return Instant.parse(value.toString());
    }
}
