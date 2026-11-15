ALTER TABLE event_identity ADD CONSTRAINT deferred_identity_has_case
    CHECK (status NOT IN ('DEFERRED','REJECTED') OR case_id IS NOT NULL);
ALTER TABLE input_receipt ADD CONSTRAINT parked_receipt_has_case
    CHECK (outcome='APPLIED' OR case_id IS NOT NULL);
