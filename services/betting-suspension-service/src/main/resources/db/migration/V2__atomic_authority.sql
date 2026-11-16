-- Prepared only. The authority and worker schemas must be in the SAME approved database.
CREATE TABLE authorization_authority (
    singleton BOOLEAN PRIMARY KEY CHECK(singleton),
    epoch UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid()
);
INSERT INTO authorization_authority(singleton) VALUES (TRUE);
CREATE TABLE authorization_lease (
    match_id TEXT PRIMARY KEY REFERENCES market_state(match_id),
    lease_id UUID NOT NULL UNIQUE,
    market_version BIGINT NOT NULL CHECK(market_version>0),
    issued_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL CHECK(valid_until>issued_at AND valid_until<=issued_at+INTERVAL '5 seconds'),
    source_checkpoint TEXT NOT NULL,
    actor TEXT NOT NULL, reason TEXT NOT NULL
);
CREATE FUNCTION lock_market_authority(p_match_id TEXT)
RETURNS TABLE(status TEXT,market_version BIGINT,source_event_id TEXT,inconsistent BOOLEAN,pending_reviews BIGINT,
              authority_epoch UUID,lease_id UUID,lease_version BIGINT,issued_at TIMESTAMPTZ,valid_until TIMESTAMPTZ,
              database_name TEXT,checked_at TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE v_market betting_control.market_state%ROWTYPE; v_epoch UUID;
BEGIN
    SELECT a.epoch INTO STRICT v_epoch FROM betting_control.authorization_authority a WHERE a.singleton=TRUE;
    SELECT m.* INTO v_market FROM betting_control.market_state m WHERE m.match_id=p_match_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'UNKNOWN'::TEXT,0::BIGINT,NULL::TEXT,TRUE,0::BIGINT,v_epoch,
            NULL::UUID,NULL::BIGINT,NULL::TIMESTAMPTZ,NULL::TIMESTAMPTZ,current_database()::TEXT,clock_timestamp();
        RETURN;
    END IF;
    RETURN QUERY SELECT v_market.status,v_market.version,v_market.last_event_id,v_market.inconsistent,
        (SELECT count(*) FROM betting_control.pending_review p WHERE p.match_id=p_match_id AND p.closed_by_event_id IS NULL),
        v_epoch,l.lease_id,l.market_version,l.issued_at,l.valid_until,current_database()::TEXT,clock_timestamp()
        FROM (SELECT 1) anchor LEFT JOIN betting_control.authorization_lease l ON l.match_id=p_match_id;
END;
$$;
REVOKE ALL ON FUNCTION lock_market_authority(TEXT) FROM PUBLIC;
REVOKE ALL ON authorization_authority,authorization_lease FROM PUBLIC;
