-- Prepared only; separate operator authorization required. No historical alert backfill.
CREATE TABLE notification.live_alert_inbox (
 alert_id UUID PRIMARY KEY,
 match_id TEXT NOT NULL CHECK(length(btrim(match_id))>0),
 source_event_id TEXT NOT NULL CHECK(length(btrim(source_event_id))>0),
 payload JSONB NOT NULL,
 UNIQUE(match_id,source_event_id)
);
