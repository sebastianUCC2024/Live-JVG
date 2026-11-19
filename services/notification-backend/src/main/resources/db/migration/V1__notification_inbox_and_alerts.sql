-- Prepared for review only. Execute through Flyway ONLY after DEV 3 approval.
CREATE SCHEMA IF NOT EXISTS notification;
CREATE TABLE notification.cursor_state (
 singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
 epoch UUID NOT NULL DEFAULT gen_random_uuid(),
 last_sequence BIGINT NOT NULL DEFAULT 0 CHECK(last_sequence >= 0)
);
INSERT INTO notification.cursor_state(singleton) VALUES(TRUE);
CREATE TABLE notification.inbox (
 source TEXT NOT NULL,
 match_id TEXT NOT NULL CHECK(length(btrim(match_id)) > 0),
 event_id TEXT NOT NULL CHECK(length(btrim(event_id)) > 0),
 payload JSONB NOT NULL,
 outcome TEXT NOT NULL CHECK(outcome IN ('ALERT','SUPPRESSED')),
 received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(source,match_id,event_id)
);
CREATE TABLE notification.alerts (
 sequence BIGINT PRIMARY KEY CHECK(sequence > 0),
 alert_id UUID NOT NULL UNIQUE,
 source TEXT NOT NULL,
 match_id TEXT NOT NULL,
 event_id TEXT NOT NULL,
 created_at TEXT NOT NULL,
 title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 120),
 message TEXT NOT NULL CHECK(length(message) BETWEEN 1 AND 500),
 UNIQUE(source,match_id,event_id),
 FOREIGN KEY(source,match_id,event_id) REFERENCES notification.inbox(source,match_id,event_id)
);
CREATE INDEX alerts_match_sequence ON notification.alerts(match_id,sequence);
