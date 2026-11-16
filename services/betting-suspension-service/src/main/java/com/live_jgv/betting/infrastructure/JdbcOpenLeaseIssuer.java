package com.live_jgv.betting.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.live_jgv.betting.application.LeaseSourceVerifier;
import java.util.*;

/** Internal only. Caller approval/caught-up checkpoint verification is required; no scheduler or HTTP adapter. */
public final class JdbcOpenLeaseIssuer {
    public record Request(String matchId,long expectedVersion,long ttlMillis,String checkpoint,String actor,String reason) {
        public Request {
            for(String value:new String[]{matchId,checkpoint,actor,reason})
                if(value==null||value.isBlank())throw new IllegalArgumentException("Audited lease request required");
            if(expectedVersion<1||ttlMillis<1||ttlMillis>5000)throw new IllegalArgumentException("Invalid lease bound");
        }
    }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final LeaseSourceVerifier verifier;
    public JdbcOpenLeaseIssuer(JdbcTemplate jdbc,TransactionTemplate tx){this(jdbc,tx,new UnavailableLeaseSourceVerifier());}
    public JdbcOpenLeaseIssuer(JdbcTemplate jdbc,TransactionTemplate tx,LeaseSourceVerifier verifier){
        this.jdbc=jdbc;this.tx=tx;this.verifier=Objects.requireNonNull(verifier);
    }
    public UUID issue(Request request){
        return Objects.requireNonNull(tx.execute(status->{
            var row=jdbc.queryForMap("SELECT status,version,inconsistent FROM betting_control.market_state WHERE match_id=? FOR UPDATE",request.matchId());
            Long pending=jdbc.queryForObject("SELECT count(*) FROM betting_control.pending_review WHERE match_id=? AND closed_by_event_id IS NULL",Long.class,request.matchId());
            if(!"OPEN".equals(row.get("status"))||Boolean.TRUE.equals(row.get("inconsistent"))
                    ||((Number)row.get("version")).longValue()!=request.expectedVersion()||pending==null||pending!=0)
                throw new IllegalStateException("Market cannot issue an opening lease");
            if(!verifier.verify(request.matchId(),request.expectedVersion(),request.checkpoint()))
                throw new IllegalStateException("Lease source cannot be verified");
            UUID id=UUID.randomUUID();
            int changed=jdbc.update("""
                WITH sampled AS (SELECT clock_timestamp() AS now)
                INSERT INTO betting_control.authorization_lease(match_id,lease_id,market_version,issued_at,valid_until,source_checkpoint,actor,reason)
                SELECT ?,?::uuid,?,sampled.now,sampled.now+(? * INTERVAL '1 millisecond'),?,?,? FROM sampled
                ON CONFLICT(match_id) DO UPDATE SET lease_id=EXCLUDED.lease_id,market_version=EXCLUDED.market_version,
                    issued_at=EXCLUDED.issued_at,valid_until=EXCLUDED.valid_until,source_checkpoint=EXCLUDED.source_checkpoint,
                    actor=EXCLUDED.actor,reason=EXCLUDED.reason
                """,request.matchId(),id.toString(),request.expectedVersion(),request.ttlMillis(),request.checkpoint(),request.actor(),request.reason());
            if(changed!=1)throw new IllegalStateException("Opening lease was not stored");return id;
        }));
    }
}
