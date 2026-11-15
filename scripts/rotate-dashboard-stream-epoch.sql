-- Administrative psql script. Run only with dashboard stopped after a restore.
-- Supply expected_epoch and new_epoch explicitly; never use during normal startup.
\set ON_ERROR_STOP on
\if :{?expected_epoch}
\else
  \echo 'Missing expected_epoch'
  DO $failure$ BEGIN RAISE EXCEPTION 'expected_epoch is required'; END; $failure$;
\endif
\if :{?new_epoch}
\else
  \echo 'Missing new_epoch'
  DO $failure$ BEGIN RAISE EXCEPTION 'new_epoch is required'; END; $failure$;
\endif

BEGIN;
WITH rotation AS (
    UPDATE dashboard.stream_epoch
       SET epoch = :'new_epoch'::uuid
     WHERE singleton = TRUE
       AND epoch = :'expected_epoch'::uuid
       AND :'new_epoch'::uuid <> :'expected_epoch'::uuid
    RETURNING epoch
)
SELECT count(*) = 1 AS rotated FROM rotation \gset
\if :rotated
  COMMIT;
  \echo 'Dashboard stream epoch rotated. Historical records unchanged.'
\else
  ROLLBACK;
  \echo 'No rotation: expected epoch mismatch or identical new epoch. Inspect before retry.'
  DO $failure$ BEGIN RAISE EXCEPTION 'Epoch rotation precondition failed'; END; $failure$;
\endif
