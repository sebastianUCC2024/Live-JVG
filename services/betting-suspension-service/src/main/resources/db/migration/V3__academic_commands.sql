-- Prepared only; requires authorization on a NEW isolated database. No authority changes.
CREATE TABLE academic_outbox (
    command_id TEXT PRIMARY KEY,
    match_id TEXT NOT NULL,
    source_event_id TEXT NOT NULL,
    source_version BIGINT NOT NULL CHECK(source_version>0),
    command_type TEXT NOT NULL CHECK(command_type IN ('SUSPEND_BETS','RESUME_BETS')),
    payload TEXT NOT NULL,
    source_payload BYTEA NOT NULL,
    source_reference_event_id TEXT,
    source_id TEXT NOT NULL, topic TEXT NOT NULL,
    partition_id INTEGER NOT NULL, record_offset BIGINT NOT NULL,
    source_headers TEXT NOT NULL,
    destination_queue TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    UNIQUE(match_id,source_event_id),
    UNIQUE(match_id,source_version),
    FOREIGN KEY(match_id,source_event_id) REFERENCES processed_event(match_id,event_id)
);
CREATE INDEX academic_outbox_pending ON academic_outbox(created_at) WHERE published_at IS NULL;
REVOKE ALL ON academic_outbox FROM PUBLIC;
