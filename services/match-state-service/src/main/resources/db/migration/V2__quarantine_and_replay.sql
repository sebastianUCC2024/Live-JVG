CREATE TABLE event_identity (
    match_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    canonical_payload TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('NEW','APPLIED','DEFERRED','REJECTED')),
    case_id UUID,
    PRIMARY KEY (match_id,event_id)
);
INSERT INTO event_identity(match_id,event_id,canonical_payload,status)
SELECT match_id,event_id,event_payload,'APPLIED' FROM accepted_event;

CREATE TABLE quarantine_case (
    case_id UUID PRIMARY KEY,
    reason_code TEXT NOT NULL,
    review_state TEXT NOT NULL CHECK (review_state IN ('QUARANTINED','DEFERRED','RESOLVED')),
    match_id TEXT,
    event_id TEXT,
    reference_event_id TEXT,
    canonical_payload TEXT,
    first_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ
);

CREATE TABLE input_receipt (
    receipt_id UUID PRIMARY KEY,
    source_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    record_timestamp BIGINT NOT NULL,
    raw_key BYTEA,
    raw_value BYTEA,
    original_headers TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('APPLIED','QUARANTINED','DEFERRED')),
    match_id TEXT,
    event_id TEXT,
    case_id UUID REFERENCES quarantine_case(case_id),
    delivery_attempts BIGINT NOT NULL DEFAULT 1,
    first_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source_id,topic,partition_id,record_offset)
);

ALTER TABLE event_identity ADD CONSTRAINT identity_case_fk FOREIGN KEY(case_id) REFERENCES quarantine_case(case_id);

CREATE TABLE replay_attempt (
    replay_id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES quarantine_case(case_id),
    actor TEXT NOT NULL CHECK (length(trim(actor))>0),
    reason TEXT NOT NULL CHECK (length(trim(reason))>0),
    expected_revision BIGINT NOT NULL CHECK (expected_revision>=0),
    result TEXT NOT NULL CHECK (result IN ('APPLIED','ALREADY_APPLIED','REJECTED','REVIEW_REQUIRED')),
    detail TEXT NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX quarantine_pending ON quarantine_case(review_state,first_seen) WHERE review_state <> 'RESOLVED';
