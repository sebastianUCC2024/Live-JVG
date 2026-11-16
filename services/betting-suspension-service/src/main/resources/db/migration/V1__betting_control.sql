-- Prepared only. Do not apply without separate database authorization.
CREATE TABLE market_state (
    match_id TEXT PRIMARY KEY,
    status TEXT NOT NULL CHECK (status IN ('OPEN','SUSPENDED','UNKNOWN')),
    version BIGINT NOT NULL CHECK (version >= 0),
    last_event_id TEXT,
    updated_at TEXT,
    inconsistent BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (status <> 'OPEN' OR (NOT inconsistent AND version > 0 AND last_event_id IS NOT NULL AND updated_at IS NOT NULL))
);
CREATE TABLE processed_event (
    match_id TEXT NOT NULL REFERENCES market_state(match_id),
    event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    team_id TEXT,
    minute INTEGER NOT NULL CHECK (minute BETWEEN 0 AND 130),
    event_timestamp TEXT NOT NULL,
    reference_event_id TEXT,
    applied_version BIGINT NOT NULL,
    canonical_payload TEXT NOT NULL,
    original_payload BYTEA NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (match_id,event_id)
);
CREATE TABLE pending_review (
    match_id TEXT NOT NULL,
    review_id TEXT NOT NULL,
    reference_event_id TEXT,
    opened_version BIGINT NOT NULL,
    closed_by_event_id TEXT,
    closed_command_id TEXT,
    PRIMARY KEY (match_id,review_id),
    FOREIGN KEY (match_id,review_id) REFERENCES processed_event(match_id,event_id),
    FOREIGN KEY (match_id,closed_by_event_id) REFERENCES processed_event(match_id,event_id)
);
CREATE TABLE input_receipt (
    source_id TEXT NOT NULL, topic TEXT NOT NULL, partition_id INTEGER NOT NULL CHECK (partition_id >= 0), record_offset BIGINT NOT NULL CHECK (record_offset >= 0),
    broker_timestamp BIGINT NOT NULL, original_key BYTEA, original_payload BYTEA, original_headers TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('PENDING','APPLIED','DUPLICATE','QUARANTINED','IDENTITY_CONFLICT')),
    reason_code TEXT, match_id TEXT, event_id TEXT, received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (match_id,event_id) REFERENCES processed_event(match_id,event_id),
    PRIMARY KEY (source_id,topic,partition_id,record_offset)
);
CREATE TABLE closure_command (
    match_id TEXT NOT NULL REFERENCES market_state(match_id), command_id TEXT NOT NULL,
    canonical_request TEXT NOT NULL, review_id TEXT NOT NULL, continuation_event_id TEXT NOT NULL,
    actor TEXT NOT NULL, reason TEXT NOT NULL, applied_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (match_id,command_id)
);
CREATE TABLE transition_history (
    match_id TEXT NOT NULL REFERENCES market_state(match_id), version BIGINT NOT NULL,
    previous_status TEXT NOT NULL, status TEXT NOT NULL,
    cause_id TEXT NOT NULL, changed_at TEXT NOT NULL,
    PRIMARY KEY (match_id,version)
);
CREATE TABLE suspension_outbox (
    message_id UUID PRIMARY KEY,
    match_id TEXT NOT NULL REFERENCES market_state(match_id), version BIGINT NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('BETTING_SUSPENDED','BETTING_RESUMED')),
    payload TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    UNIQUE (match_id,version)
);
CREATE INDEX pending_review_open ON pending_review(match_id) WHERE closed_by_event_id IS NULL;
CREATE INDEX suspension_outbox_pending ON suspension_outbox(created_at) WHERE published_at IS NULL;
