-- Prepared only. No public grants; roles and shared database require explicit approval.
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM operation WHERE status='ACCEPTED') THEN
        RAISE EXCEPTION 'Existing accepted operations require an explicit authorization migration review';
    END IF;
END $$;
CREATE TABLE operation_authorization (
    operation_id TEXT PRIMARY KEY REFERENCES operation(operation_id),
    command_id TEXT NOT NULL UNIQUE REFERENCES command_inbox(command_id),
    match_id TEXT NOT NULL,
    canonical_payload TEXT NOT NULL,
    market_version BIGINT NOT NULL,
    authority_epoch UUID NOT NULL, lease_id UUID NOT NULL,
    authorized_at TIMESTAMPTZ NOT NULL, valid_until TIMESTAMPTZ NOT NULL,
    database_name TEXT NOT NULL, backend_pid INTEGER NOT NULL, transaction_id BIGINT NOT NULL
);
CREATE FUNCTION record_atomic_authorization(p_operation TEXT,p_canonical TEXT,p_version BIGINT,p_epoch UUID,p_lease UUID)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_operation RECORD; v_source RECORD;
BEGIN
    SELECT o.*,c.canonical_payload INTO STRICT v_operation FROM betting_worker.operation o
        JOIN betting_worker.command_inbox c ON c.command_id=o.command_id
        WHERE o.operation_id=p_operation AND o.status='RECEIVED';
    IF v_operation.canonical_payload IS DISTINCT FROM p_canonical THEN
        RAISE EXCEPTION 'Authorization command content mismatch';
    END IF;
    SELECT * INTO STRICT v_source FROM betting_control.lock_market_authority(v_operation.match_id);
    IF v_source.status<>'OPEN' OR v_source.inconsistent OR v_source.pending_reviews<>0
        OR v_source.market_version IS DISTINCT FROM p_version OR v_source.authority_epoch IS DISTINCT FROM p_epoch
        OR v_source.lease_id IS DISTINCT FROM p_lease OR v_source.lease_version IS DISTINCT FROM p_version
        OR v_source.issued_at IS NULL OR v_source.issued_at>clock_timestamp()
        OR v_source.valid_until IS NULL OR v_source.valid_until<=clock_timestamp() THEN RETURN FALSE;
    END IF;
    INSERT INTO betting_worker.operation_authorization(operation_id,command_id,match_id,canonical_payload,market_version,
        authority_epoch,lease_id,authorized_at,valid_until,database_name,backend_pid,transaction_id)
        VALUES(p_operation,v_operation.command_id,v_operation.match_id,p_canonical,p_version,p_epoch,p_lease,
            clock_timestamp(),v_source.valid_until,current_database(),pg_backend_pid(),txid_current());
    RETURN TRUE;
END;
$$;
CREATE FUNCTION require_transactional_authorization() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF NEW.status='ACCEPTED' AND NOT EXISTS (
        SELECT 1 FROM betting_worker.operation_authorization a
        JOIN betting_worker.command_inbox c ON c.command_id=a.command_id
        WHERE a.operation_id=NEW.operation_id AND a.command_id=NEW.command_id AND a.match_id=NEW.match_id
            AND a.canonical_payload=c.canonical_payload AND a.transaction_id=txid_current()
            AND a.database_name=current_database() AND a.backend_pid=pg_backend_pid()
            AND a.valid_until>clock_timestamp()) THEN
        RAISE EXCEPTION 'Accepted operation requires current transaction authorization' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER accepted_requires_atomic_authorization AFTER INSERT OR UPDATE ON operation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_transactional_authorization();
REVOKE ALL ON operation_authorization FROM PUBLIC;
REVOKE ALL ON FUNCTION record_atomic_authorization(TEXT,TEXT,BIGINT,UUID,UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION require_transactional_authorization() FROM PUBLIC;

-- Also forbid committing a grant on its own, even if a caller bypasses the Java session protocol.
CREATE FUNCTION require_accepted_authorization() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    IF NEW.transaction_id<>txid_current() OR NEW.database_name<>current_database() OR NEW.backend_pid<>pg_backend_pid()
        OR NEW.valid_until<=clock_timestamp() OR NOT EXISTS (
            SELECT 1 FROM betting_worker.operation o
            WHERE o.operation_id=NEW.operation_id AND o.command_id=NEW.command_id AND o.match_id=NEW.match_id AND o.status='ACCEPTED') THEN
        RAISE EXCEPTION 'Authorization and acceptance must commit together' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER authorization_requires_accepted_operation AFTER INSERT ON operation_authorization
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_accepted_authorization();
REVOKE ALL ON FUNCTION require_accepted_authorization() FROM PUBLIC;
