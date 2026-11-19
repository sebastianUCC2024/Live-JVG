-- Preserve V1 history; align the archive with the core identity (match_id,event_id).
ALTER TABLE input_receipt ADD COLUMN match_id TEXT;
UPDATE input_receipt r SET match_id=e.match_id FROM archived_event e WHERE r.event_id=e.event_id;
ALTER TABLE input_receipt DROP CONSTRAINT input_receipt_event_id_fkey;
ALTER TABLE archived_event DROP CONSTRAINT archived_event_event_id_key;
ALTER TABLE archived_event ADD CONSTRAINT archived_event_match_event_key UNIQUE(match_id,event_id);
ALTER TABLE input_receipt ADD CONSTRAINT archive_receipt_event_fk
    FOREIGN KEY(match_id,event_id) REFERENCES archived_event(match_id,event_id);
ALTER TABLE input_receipt ADD CONSTRAINT archive_receipt_identity_complete CHECK((event_id IS NULL)=(match_id IS NULL));
