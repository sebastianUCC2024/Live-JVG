CREATE TABLE match_catalog (
    match_id TEXT PRIMARY KEY CHECK (length(trim(match_id)) > 0),
    home_team_id TEXT NOT NULL CHECK (length(trim(home_team_id)) > 0),
    away_team_id TEXT NOT NULL CHECK (length(trim(away_team_id)) > 0),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    rules_version INTEGER NOT NULL DEFAULT 1 CHECK (rules_version = 1),
    CHECK (home_team_id <> away_team_id)
);

CREATE TABLE accepted_event (
    match_id TEXT NOT NULL REFERENCES match_catalog(match_id),
    event_id TEXT NOT NULL,
    application_seq BIGINT NOT NULL CHECK (application_seq > 0),
    -- Text preserves Instant nanoseconds and the complete validated event.
    event_payload TEXT NOT NULL,
    PRIMARY KEY (match_id, event_id),
    UNIQUE (match_id, application_seq)
);

CREATE TABLE score_outbox (
    match_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    application_seq BIGINT NOT NULL,
    payload TEXT NOT NULL,
    exchange_name TEXT NOT NULL,
    routing_key TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (match_id, event_id),
    FOREIGN KEY (match_id, event_id) REFERENCES accepted_event(match_id, event_id),
    UNIQUE (match_id, application_seq)
);

CREATE INDEX score_outbox_pending ON score_outbox(match_id, application_seq) WHERE NOT published;
