-- Prepared only; no application operation, grant, lease or market_guard modification.
CREATE TABLE academic_command (
    command_id TEXT PRIMARY KEY, match_id TEXT NOT NULL, source_event_id TEXT NOT NULL,
    command_type TEXT NOT NULL CHECK(command_type IN ('SUSPEND_BETS','RESUME_BETS')),
    command_timestamp TEXT NOT NULL, canonical_payload TEXT NOT NULL,
    simulated_status TEXT NOT NULL CHECK(simulated_status IN ('SIMULATED_SUSPENDED','SIMULATED_RESUMED')),
    ordering_status TEXT NOT NULL CHECK(ordering_status='NO_AUTHORITATIVE_SEQUENCE'),
    delivery_attempts BIGINT NOT NULL DEFAULT 1 CHECK(delivery_attempts>0),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(match_id,source_event_id),
    CHECK((command_type='SUSPEND_BETS' AND simulated_status='SIMULATED_SUSPENDED') OR
          (command_type='RESUME_BETS' AND simulated_status='SIMULATED_RESUMED'))
);
CREATE TABLE academic_delivery (
    delivery_id TEXT PRIMARY KEY, command_id TEXT,
    source_id TEXT NOT NULL, broker_message_id TEXT, exchange_name TEXT NOT NULL, routing_key TEXT NOT NULL,
    original_payload BYTEA, original_headers TEXT NOT NULL, outcome TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX academic_delivery_command ON academic_delivery(command_id,received_at);
REVOKE ALL ON academic_command,academic_delivery FROM PUBLIC;
