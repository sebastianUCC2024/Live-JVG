-- Proposed only; no historical backfill or implicit activation.
CREATE TABLE alert_outbox (
 alert_id UUID PRIMARY KEY,
 match_id TEXT NOT NULL,
 event_id TEXT NOT NULL,
 application_seq BIGINT NOT NULL CHECK(application_seq>0),
 payload TEXT NOT NULL,
 exchange_name TEXT NOT NULL CHECK(exchange_name='live_alerts'),
 published BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(match_id,event_id) REFERENCES accepted_event(match_id,event_id),
 UNIQUE(match_id,event_id), UNIQUE(match_id,application_seq)
);
CREATE INDEX alert_outbox_pending ON alert_outbox(match_id,application_seq) WHERE NOT published;
