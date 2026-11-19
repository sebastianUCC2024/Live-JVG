-- Prepared only: applying this migration requires separate infrastructure authorization.
CREATE TABLE archived_event (
    seq BIGSERIAL PRIMARY KEY,
    event_id TEXT NOT NULL UNIQUE CHECK (length(trim(event_id)) > 0),
    match_id TEXT NOT NULL CHECK (length(trim(match_id)) > 0),
    event_type TEXT NOT NULL CHECK (event_type IN ('GOAL','VAR_CHECK','GOAL_ANNULLED','MATCH_CONTINUES')),
    team_id TEXT,
    minute INTEGER NOT NULL CHECK (minute BETWEEN 0 AND 130),
    event_timestamp TEXT NOT NULL,
    reference_event_id TEXT,
    original_payload BYTEA NOT NULL,
    canonical_payload TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (event_type <> 'GOAL' OR (team_id IS NOT NULL AND length(trim(team_id)) > 0)),
    CHECK (event_type <> 'GOAL_ANNULLED' OR (reference_event_id IS NOT NULL AND length(trim(reference_event_id)) > 0))
);
CREATE INDEX archived_event_match_sequence ON archived_event(match_id, seq);

CREATE TABLE input_receipt (
    source_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
    record_offset BIGINT NOT NULL CHECK (record_offset >= 0),
    broker_timestamp BIGINT NOT NULL,
    original_key BYTEA,
    original_payload BYTEA,
    original_headers TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('PENDING','STORED','DUPLICATE','QUARANTINED','IDENTITY_CONFLICT')),
    event_id TEXT REFERENCES archived_event(event_id),
    reason_code TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_id, topic, partition_id, record_offset)
);
-- Quarantine is a durable receipt with raw evidence and an explicit outcome/reason.
CREATE INDEX archive_receipt_review ON input_receipt(received_at)
    WHERE outcome IN ('QUARANTINED','IDENTITY_CONFLICT');
