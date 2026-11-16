-- Prepared only: do not apply to any database without separate authorization.
CREATE TABLE market_guard (
    match_id TEXT PRIMARY KEY,
    authority TEXT, epoch TEXT, evidence_revision BIGINT NOT NULL DEFAULT 0 CHECK(evidence_revision>=0),
    status TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(status IN ('OPEN','SUSPENDED','UNKNOWN')),
    issued_at TEXT, expires_at TEXT, frozen BOOLEAN NOT NULL DEFAULT FALSE,
    guard_version BIGINT NOT NULL DEFAULT 0 CHECK(guard_version>=0)
);
CREATE TABLE command_inbox (
    command_id TEXT PRIMARY KEY, operation_id TEXT NOT NULL, canonical_payload TEXT NOT NULL,
    original_payload BYTEA NOT NULL, outcome TEXT NOT NULL CHECK(outcome IN ('PENDING','PROCESSED','IDENTITY_CONFLICT')),
    delivery_attempts BIGINT NOT NULL DEFAULT 1 CHECK(delivery_attempts>0),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE operation (
    operation_id TEXT PRIMARY KEY, command_id TEXT NOT NULL UNIQUE REFERENCES command_inbox(command_id),
    match_id TEXT NOT NULL REFERENCES market_guard(match_id), simulated_user_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('RECEIVED','ACCEPTED','REJECTED','BLOCKED','FAILED')),
    reason TEXT, version BIGINT NOT NULL CHECK(version>=0),
    command_timestamp TEXT NOT NULL, received_at TEXT NOT NULL, updated_at TEXT NOT NULL,
    CHECK((status='RECEIVED' AND version=0 AND reason IS NULL) OR (status<>'RECEIVED' AND version>0 AND reason IS NOT NULL))
);
CREATE TABLE operation_transition (
    operation_id TEXT NOT NULL REFERENCES operation(operation_id), version BIGINT NOT NULL,
    previous_status TEXT, status TEXT NOT NULL, reason TEXT, changed_at TEXT NOT NULL,
    PRIMARY KEY(operation_id,version)
);
CREATE TABLE operation_evidence (
    operation_id TEXT PRIMARY KEY REFERENCES operation(operation_id),
    observed_payload TEXT NOT NULL, guard_payload TEXT NOT NULL,
    source_available BOOLEAN NOT NULL, decision_reason TEXT NOT NULL, observed_at TEXT NOT NULL
);
CREATE TABLE message_audit (
    audit_id UUID PRIMARY KEY, source_id TEXT NOT NULL, broker_message_id TEXT,
    exchange_name TEXT NOT NULL, routing_key TEXT NOT NULL,
    original_payload BYTEA, original_headers TEXT NOT NULL,
    command_id TEXT, reason TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX operation_match_history ON operation(match_id,received_at,operation_id);
CREATE INDEX message_audit_command ON message_audit(command_id,received_at);
