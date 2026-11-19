-- Prepared only. No historical guess/backfill: legacy rows remain NULL.
ALTER TABLE inbox ADD COLUMN source_application_seq BIGINT CHECK(source_application_seq>0);
CREATE UNIQUE INDEX inbox_source_sequence ON inbox(match_id,source_application_seq) WHERE source_application_seq IS NOT NULL;
