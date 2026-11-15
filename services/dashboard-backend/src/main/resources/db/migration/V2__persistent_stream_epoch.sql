-- PostgreSQL 17 provides gen_random_uuid() without an extension.
-- Epoch survives ordinary restarts. Restore operators must rotate it explicitly.
CREATE TABLE IF NOT EXISTS stream_epoch (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    epoch UUID NOT NULL
);

INSERT INTO stream_epoch(singleton, epoch)
VALUES (TRUE, gen_random_uuid())
ON CONFLICT (singleton) DO NOTHING;
