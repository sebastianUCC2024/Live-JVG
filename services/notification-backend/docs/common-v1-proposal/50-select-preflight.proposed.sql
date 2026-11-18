-- PROPOSAL ONLY. DBA session, future approval required; never use production.
\set ON_ERROR_STOP on
\if :{?operator_approved_project}
\else
\echo 'Missing operator_approved_project; refusal before any DDL/DML'
\quit 3
\endif
\if :{?expected_system_identifier}
\else
\echo 'Missing expected_system_identifier; refusal before any DDL/DML'
\quit 3
\endif
\if :{?adopt_empty_database}
\else
\echo 'Missing adopt_empty_database; refusal before any DDL/DML'
\quit 3
\endif
-- approved target and cluster identity
SELECT (current_database()='live_jvg' AND current_setting('server_version_num')::int BETWEEN 170000 AND 179999 AND :'operator_approved_project'='live-jvg-e2e-5c84548cf006' AND :'adopt_empty_database'='true' AND (SELECT system_identifier::text FROM pg_control_system())=:'expected_system_identifier') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: approved target and cluster identity'
\quit 3
\endif
-- no other application databases on server
SELECT (NOT EXISTS(SELECT 1 FROM pg_database WHERE NOT datistemplate AND datname NOT IN ('postgres','live_jvg'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no other application databases on server'
\quit 3
\endif
-- no foreign schemas
SELECT (NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('public','information_schema','match_state','dashboard','notification','archiver','betting_control','betting_worker'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no foreign schemas'
\quit 3
\endif
-- database marker belongs to this run
SELECT (obj_description((SELECT oid FROM pg_database WHERE datname=current_database()),'pg_database') IS NOT DISTINCT FROM ('live-jvg/common-v1/5c84548cf006/' || :'expected_system_identifier')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: database marker belongs to this run'
\quit 3
\endif
-- readable match_state.match_catalog
SELECT (to_regclass('match_state.match_catalog') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_match_state_app','match_state','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.match_catalog','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable match_state.match_catalog'
\quit 3
\endif
-- passive runtime has no DML match_state.match_catalog
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.match_catalog','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.match_catalog','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.match_catalog','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.match_catalog','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML match_state.match_catalog'
\quit 3
\endif
SELECT * FROM match_state.match_catalog LIMIT 0;
-- readable match_state.accepted_event
SELECT (to_regclass('match_state.accepted_event') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_match_state_app','match_state','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.accepted_event','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable match_state.accepted_event'
\quit 3
\endif
-- passive runtime has no DML match_state.accepted_event
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.accepted_event','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.accepted_event','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.accepted_event','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.accepted_event','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML match_state.accepted_event'
\quit 3
\endif
SELECT * FROM match_state.accepted_event LIMIT 0;
-- readable match_state.score_outbox
SELECT (to_regclass('match_state.score_outbox') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_match_state_app','match_state','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.score_outbox','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable match_state.score_outbox'
\quit 3
\endif
-- passive runtime has no DML match_state.score_outbox
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.score_outbox','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.score_outbox','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.score_outbox','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.score_outbox','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML match_state.score_outbox'
\quit 3
\endif
SELECT * FROM match_state.score_outbox LIMIT 0;
-- readable match_state.event_identity
SELECT (to_regclass('match_state.event_identity') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_match_state_app','match_state','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.event_identity','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable match_state.event_identity'
\quit 3
\endif
-- passive runtime has no DML match_state.event_identity
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.event_identity','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.event_identity','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.event_identity','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.event_identity','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML match_state.event_identity'
\quit 3
\endif
SELECT * FROM match_state.event_identity LIMIT 0;
-- readable match_state.quarantine_case
SELECT (to_regclass('match_state.quarantine_case') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_match_state_app','match_state','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.quarantine_case','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable match_state.quarantine_case'
\quit 3
\endif
-- passive runtime has no DML match_state.quarantine_case
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.quarantine_case','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.quarantine_case','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.quarantine_case','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.quarantine_case','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML match_state.quarantine_case'
\quit 3
\endif
SELECT * FROM match_state.quarantine_case LIMIT 0;
-- readable match_state.input_receipt
SELECT (to_regclass('match_state.input_receipt') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_match_state_app','match_state','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.input_receipt','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable match_state.input_receipt'
\quit 3
\endif
-- passive runtime has no DML match_state.input_receipt
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.input_receipt','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.input_receipt','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.input_receipt','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.input_receipt','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML match_state.input_receipt'
\quit 3
\endif
SELECT * FROM match_state.input_receipt LIMIT 0;
-- readable match_state.replay_attempt
SELECT (to_regclass('match_state.replay_attempt') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_match_state_app','match_state','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.replay_attempt','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable match_state.replay_attempt'
\quit 3
\endif
-- passive runtime has no DML match_state.replay_attempt
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.replay_attempt','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.replay_attempt','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.replay_attempt','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.replay_attempt','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML match_state.replay_attempt'
\quit 3
\endif
SELECT * FROM match_state.replay_attempt LIMIT 0;
-- readable match_state.alert_outbox
SELECT (to_regclass('match_state.alert_outbox') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_match_state_app','match_state','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.alert_outbox','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable match_state.alert_outbox'
\quit 3
\endif
-- passive runtime has no DML match_state.alert_outbox
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.alert_outbox','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_match_state_app','match_state.alert_outbox','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.alert_outbox','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_match_state_app','match_state.alert_outbox','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML match_state.alert_outbox'
\quit 3
\endif
SELECT * FROM match_state.alert_outbox LIMIT 0;
-- runtime cannot create objects match_state
SELECT (NOT has_schema_privilege('dev3_it_5c84548cf006_match_state_app','match_state','CREATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime cannot create objects match_state'
\quit 3
\endif
SELECT installed_rank,version,description,checksum,success FROM match_state.flyway_schema_history ORDER BY installed_rank;
-- migration history version match_state.1
SELECT (EXISTS(SELECT 1 FROM match_state.flyway_schema_history WHERE version='1' AND success) AND NOT EXISTS(SELECT 1 FROM match_state.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version match_state.1'
\quit 3
\endif
-- migration history version match_state.2
SELECT (EXISTS(SELECT 1 FROM match_state.flyway_schema_history WHERE version='2' AND success) AND NOT EXISTS(SELECT 1 FROM match_state.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version match_state.2'
\quit 3
\endif
-- migration history version match_state.3
SELECT (EXISTS(SELECT 1 FROM match_state.flyway_schema_history WHERE version='3' AND success) AND NOT EXISTS(SELECT 1 FROM match_state.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version match_state.3'
\quit 3
\endif
-- migration history version match_state.4
SELECT (EXISTS(SELECT 1 FROM match_state.flyway_schema_history WHERE version='4' AND success) AND NOT EXISTS(SELECT 1 FROM match_state.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version match_state.4'
\quit 3
\endif
-- no unexpected migration versions match_state
SELECT (NOT EXISTS(SELECT 1 FROM match_state.flyway_schema_history WHERE version IS NULL OR version NOT IN ('1','2','3','4'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no unexpected migration versions match_state'
\quit 3
\endif
-- readable dashboard.matches
SELECT (to_regclass('dashboard.matches') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.matches','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable dashboard.matches'
\quit 3
\endif
-- passive runtime has no DML dashboard.matches
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.matches','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.matches','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.matches','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.matches','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML dashboard.matches'
\quit 3
\endif
SELECT * FROM dashboard.matches LIMIT 0;
-- readable dashboard.inbox
SELECT (to_regclass('dashboard.inbox') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.inbox','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable dashboard.inbox'
\quit 3
\endif
-- passive runtime has no DML dashboard.inbox
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.inbox','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.inbox','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.inbox','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.inbox','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML dashboard.inbox'
\quit 3
\endif
SELECT * FROM dashboard.inbox LIMIT 0;
-- readable dashboard.stream_epoch
SELECT (to_regclass('dashboard.stream_epoch') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.stream_epoch','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable dashboard.stream_epoch'
\quit 3
\endif
-- passive runtime has no DML dashboard.stream_epoch
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.stream_epoch','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.stream_epoch','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.stream_epoch','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard.stream_epoch','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML dashboard.stream_epoch'
\quit 3
\endif
SELECT * FROM dashboard.stream_epoch LIMIT 0;
-- runtime cannot create objects dashboard
SELECT (NOT has_schema_privilege('dev3_it_5c84548cf006_dashboard_app','dashboard','CREATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime cannot create objects dashboard'
\quit 3
\endif
SELECT installed_rank,version,description,checksum,success FROM dashboard.flyway_schema_history ORDER BY installed_rank;
-- migration history version dashboard.1
SELECT (EXISTS(SELECT 1 FROM dashboard.flyway_schema_history WHERE version='1' AND success) AND NOT EXISTS(SELECT 1 FROM dashboard.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version dashboard.1'
\quit 3
\endif
-- migration history version dashboard.2
SELECT (EXISTS(SELECT 1 FROM dashboard.flyway_schema_history WHERE version='2' AND success) AND NOT EXISTS(SELECT 1 FROM dashboard.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version dashboard.2'
\quit 3
\endif
-- migration history version dashboard.3
SELECT (EXISTS(SELECT 1 FROM dashboard.flyway_schema_history WHERE version='3' AND success) AND NOT EXISTS(SELECT 1 FROM dashboard.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version dashboard.3'
\quit 3
\endif
-- no unexpected migration versions dashboard
SELECT (NOT EXISTS(SELECT 1 FROM dashboard.flyway_schema_history WHERE version IS NULL OR version NOT IN ('1','2','3'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no unexpected migration versions dashboard'
\quit 3
\endif
-- readable notification.cursor_state
SELECT (to_regclass('notification.cursor_state') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_notification_app','notification','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.cursor_state','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable notification.cursor_state'
\quit 3
\endif
-- passive runtime has no DML notification.cursor_state
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_notification_app','notification.cursor_state','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_notification_app','notification.cursor_state','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.cursor_state','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.cursor_state','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML notification.cursor_state'
\quit 3
\endif
SELECT * FROM notification.cursor_state LIMIT 0;
-- readable notification.inbox
SELECT (to_regclass('notification.inbox') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_notification_app','notification','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.inbox','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable notification.inbox'
\quit 3
\endif
-- passive runtime has no DML notification.inbox
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_notification_app','notification.inbox','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_notification_app','notification.inbox','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.inbox','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.inbox','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML notification.inbox'
\quit 3
\endif
SELECT * FROM notification.inbox LIMIT 0;
-- readable notification.alerts
SELECT (to_regclass('notification.alerts') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_notification_app','notification','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.alerts','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable notification.alerts'
\quit 3
\endif
-- passive runtime has no DML notification.alerts
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_notification_app','notification.alerts','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_notification_app','notification.alerts','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.alerts','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.alerts','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML notification.alerts'
\quit 3
\endif
SELECT * FROM notification.alerts LIMIT 0;
-- readable notification.live_alert_inbox
SELECT (to_regclass('notification.live_alert_inbox') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_notification_app','notification','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.live_alert_inbox','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable notification.live_alert_inbox'
\quit 3
\endif
-- passive runtime has no DML notification.live_alert_inbox
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_notification_app','notification.live_alert_inbox','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_notification_app','notification.live_alert_inbox','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.live_alert_inbox','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_notification_app','notification.live_alert_inbox','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML notification.live_alert_inbox'
\quit 3
\endif
SELECT * FROM notification.live_alert_inbox LIMIT 0;
-- runtime cannot create objects notification
SELECT (NOT has_schema_privilege('dev3_it_5c84548cf006_notification_app','notification','CREATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime cannot create objects notification'
\quit 3
\endif
SELECT installed_rank,version,description,checksum,success FROM notification.flyway_schema_history ORDER BY installed_rank;
-- migration history version notification.1
SELECT (EXISTS(SELECT 1 FROM notification.flyway_schema_history WHERE version='1' AND success) AND NOT EXISTS(SELECT 1 FROM notification.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version notification.1'
\quit 3
\endif
-- migration history version notification.2
SELECT (EXISTS(SELECT 1 FROM notification.flyway_schema_history WHERE version='2' AND success) AND NOT EXISTS(SELECT 1 FROM notification.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version notification.2'
\quit 3
\endif
-- no unexpected migration versions notification
SELECT (NOT EXISTS(SELECT 1 FROM notification.flyway_schema_history WHERE version IS NULL OR version NOT IN ('1','2'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no unexpected migration versions notification'
\quit 3
\endif
-- readable archiver.archived_event
SELECT (to_regclass('archiver.archived_event') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_archive_app','archiver','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_archive_app','archiver.archived_event','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable archiver.archived_event'
\quit 3
\endif
-- passive runtime has no DML archiver.archived_event
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_archive_app','archiver.archived_event','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_archive_app','archiver.archived_event','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_archive_app','archiver.archived_event','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_archive_app','archiver.archived_event','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML archiver.archived_event'
\quit 3
\endif
SELECT * FROM archiver.archived_event LIMIT 0;
-- readable archiver.input_receipt
SELECT (to_regclass('archiver.input_receipt') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_archive_app','archiver','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_archive_app','archiver.input_receipt','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable archiver.input_receipt'
\quit 3
\endif
-- passive runtime has no DML archiver.input_receipt
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_archive_app','archiver.input_receipt','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_archive_app','archiver.input_receipt','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_archive_app','archiver.input_receipt','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_archive_app','archiver.input_receipt','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML archiver.input_receipt'
\quit 3
\endif
SELECT * FROM archiver.input_receipt LIMIT 0;
-- runtime cannot create objects archiver
SELECT (NOT has_schema_privilege('dev3_it_5c84548cf006_archive_app','archiver','CREATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime cannot create objects archiver'
\quit 3
\endif
SELECT installed_rank,version,description,checksum,success FROM archiver.flyway_schema_history ORDER BY installed_rank;
-- migration history version archiver.1
SELECT (EXISTS(SELECT 1 FROM archiver.flyway_schema_history WHERE version='1' AND success) AND NOT EXISTS(SELECT 1 FROM archiver.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version archiver.1'
\quit 3
\endif
-- migration history version archiver.2
SELECT (EXISTS(SELECT 1 FROM archiver.flyway_schema_history WHERE version='2' AND success) AND NOT EXISTS(SELECT 1 FROM archiver.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version archiver.2'
\quit 3
\endif
-- no unexpected migration versions archiver
SELECT (NOT EXISTS(SELECT 1 FROM archiver.flyway_schema_history WHERE version IS NULL OR version NOT IN ('1','2'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no unexpected migration versions archiver'
\quit 3
\endif
-- readable betting_control.market_state
SELECT (to_regclass('betting_control.market_state') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.market_state','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.market_state'
\quit 3
\endif
-- passive runtime has no DML betting_control.market_state
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.market_state','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.market_state','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.market_state','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.market_state','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.market_state'
\quit 3
\endif
SELECT * FROM betting_control.market_state LIMIT 0;
-- readable betting_control.processed_event
SELECT (to_regclass('betting_control.processed_event') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.processed_event','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.processed_event'
\quit 3
\endif
-- passive runtime has no DML betting_control.processed_event
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.processed_event','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.processed_event','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.processed_event','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.processed_event','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.processed_event'
\quit 3
\endif
SELECT * FROM betting_control.processed_event LIMIT 0;
-- readable betting_control.pending_review
SELECT (to_regclass('betting_control.pending_review') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.pending_review','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.pending_review'
\quit 3
\endif
-- passive runtime has no DML betting_control.pending_review
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.pending_review','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.pending_review','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.pending_review','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.pending_review','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.pending_review'
\quit 3
\endif
SELECT * FROM betting_control.pending_review LIMIT 0;
-- readable betting_control.input_receipt
SELECT (to_regclass('betting_control.input_receipt') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.input_receipt','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.input_receipt'
\quit 3
\endif
-- passive runtime has no DML betting_control.input_receipt
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.input_receipt','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.input_receipt','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.input_receipt','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.input_receipt','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.input_receipt'
\quit 3
\endif
SELECT * FROM betting_control.input_receipt LIMIT 0;
-- readable betting_control.closure_command
SELECT (to_regclass('betting_control.closure_command') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.closure_command','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.closure_command'
\quit 3
\endif
-- passive runtime has no DML betting_control.closure_command
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.closure_command','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.closure_command','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.closure_command','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.closure_command','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.closure_command'
\quit 3
\endif
SELECT * FROM betting_control.closure_command LIMIT 0;
-- readable betting_control.transition_history
SELECT (to_regclass('betting_control.transition_history') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.transition_history','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.transition_history'
\quit 3
\endif
-- passive runtime has no DML betting_control.transition_history
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.transition_history','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.transition_history','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.transition_history','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.transition_history','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.transition_history'
\quit 3
\endif
SELECT * FROM betting_control.transition_history LIMIT 0;
-- readable betting_control.suspension_outbox
SELECT (to_regclass('betting_control.suspension_outbox') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.suspension_outbox','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.suspension_outbox'
\quit 3
\endif
-- passive runtime has no DML betting_control.suspension_outbox
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.suspension_outbox','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.suspension_outbox','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.suspension_outbox','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.suspension_outbox','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.suspension_outbox'
\quit 3
\endif
SELECT * FROM betting_control.suspension_outbox LIMIT 0;
-- readable betting_control.academic_outbox
SELECT (to_regclass('betting_control.academic_outbox') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.academic_outbox','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.academic_outbox'
\quit 3
\endif
-- passive runtime has no DML betting_control.academic_outbox
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.academic_outbox','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.academic_outbox','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.academic_outbox','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.academic_outbox','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.academic_outbox'
\quit 3
\endif
SELECT * FROM betting_control.academic_outbox LIMIT 0;
-- readable betting_control.authorization_authority
SELECT (to_regclass('betting_control.authorization_authority') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_authority','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.authorization_authority'
\quit 3
\endif
-- passive runtime has no DML betting_control.authorization_authority
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_authority','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_authority','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_authority','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_authority','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.authorization_authority'
\quit 3
\endif
SELECT * FROM betting_control.authorization_authority LIMIT 0;
-- readable betting_control.authorization_lease
SELECT (to_regclass('betting_control.authorization_lease') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_lease','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_control.authorization_lease'
\quit 3
\endif
-- passive runtime has no DML betting_control.authorization_lease
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_lease','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_lease','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_lease','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_lease','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_control.authorization_lease'
\quit 3
\endif
SELECT * FROM betting_control.authorization_lease LIMIT 0;
-- runtime cannot create objects betting_control
SELECT (NOT has_schema_privilege('dev3_it_5c84548cf006_control_app','betting_control','CREATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime cannot create objects betting_control'
\quit 3
\endif
SELECT installed_rank,version,description,checksum,success FROM betting_control.flyway_schema_history ORDER BY installed_rank;
-- migration history version betting_control.1
SELECT (EXISTS(SELECT 1 FROM betting_control.flyway_schema_history WHERE version='1' AND success) AND NOT EXISTS(SELECT 1 FROM betting_control.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version betting_control.1'
\quit 3
\endif
-- migration history version betting_control.2
SELECT (EXISTS(SELECT 1 FROM betting_control.flyway_schema_history WHERE version='2' AND success) AND NOT EXISTS(SELECT 1 FROM betting_control.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version betting_control.2'
\quit 3
\endif
-- migration history version betting_control.3
SELECT (EXISTS(SELECT 1 FROM betting_control.flyway_schema_history WHERE version='3' AND success) AND NOT EXISTS(SELECT 1 FROM betting_control.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version betting_control.3'
\quit 3
\endif
-- no unexpected migration versions betting_control
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.flyway_schema_history WHERE version IS NULL OR version NOT IN ('1','2','3'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no unexpected migration versions betting_control'
\quit 3
\endif
-- readable betting_worker.market_guard
SELECT (to_regclass('betting_worker.market_guard') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.market_guard','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_worker.market_guard'
\quit 3
\endif
-- passive runtime has no DML betting_worker.market_guard
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.market_guard','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.market_guard','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.market_guard','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.market_guard','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_worker.market_guard'
\quit 3
\endif
SELECT * FROM betting_worker.market_guard LIMIT 0;
-- readable betting_worker.command_inbox
SELECT (to_regclass('betting_worker.command_inbox') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.command_inbox','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_worker.command_inbox'
\quit 3
\endif
-- passive runtime has no DML betting_worker.command_inbox
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.command_inbox','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.command_inbox','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.command_inbox','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.command_inbox','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_worker.command_inbox'
\quit 3
\endif
SELECT * FROM betting_worker.command_inbox LIMIT 0;
-- readable betting_worker.operation
SELECT (to_regclass('betting_worker.operation') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_worker.operation'
\quit 3
\endif
-- passive runtime has no DML betting_worker.operation
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_worker.operation'
\quit 3
\endif
SELECT * FROM betting_worker.operation LIMIT 0;
-- readable betting_worker.operation_transition
SELECT (to_regclass('betting_worker.operation_transition') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_transition','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_worker.operation_transition'
\quit 3
\endif
-- passive runtime has no DML betting_worker.operation_transition
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_transition','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_transition','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_transition','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_transition','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_worker.operation_transition'
\quit 3
\endif
SELECT * FROM betting_worker.operation_transition LIMIT 0;
-- readable betting_worker.operation_evidence
SELECT (to_regclass('betting_worker.operation_evidence') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_evidence','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_worker.operation_evidence'
\quit 3
\endif
-- passive runtime has no DML betting_worker.operation_evidence
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_evidence','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_evidence','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_evidence','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_evidence','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_worker.operation_evidence'
\quit 3
\endif
SELECT * FROM betting_worker.operation_evidence LIMIT 0;
-- readable betting_worker.message_audit
SELECT (to_regclass('betting_worker.message_audit') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.message_audit','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_worker.message_audit'
\quit 3
\endif
-- passive runtime has no DML betting_worker.message_audit
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.message_audit','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.message_audit','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.message_audit','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.message_audit','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_worker.message_audit'
\quit 3
\endif
SELECT * FROM betting_worker.message_audit LIMIT 0;
-- readable betting_worker.academic_command
SELECT (to_regclass('betting_worker.academic_command') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_command','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_worker.academic_command'
\quit 3
\endif
-- passive runtime has no DML betting_worker.academic_command
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_command','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_command','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_command','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_command','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_worker.academic_command'
\quit 3
\endif
SELECT * FROM betting_worker.academic_command LIMIT 0;
-- readable betting_worker.academic_delivery
SELECT (to_regclass('betting_worker.academic_delivery') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_delivery','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_worker.academic_delivery'
\quit 3
\endif
-- passive runtime has no DML betting_worker.academic_delivery
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_delivery','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_delivery','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_delivery','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.academic_delivery','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_worker.academic_delivery'
\quit 3
\endif
SELECT * FROM betting_worker.academic_delivery LIMIT 0;
-- readable betting_worker.operation_authorization
SELECT (to_regclass('betting_worker.operation_authorization') IS NOT NULL AND has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','USAGE') AND has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_authorization','SELECT')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: readable betting_worker.operation_authorization'
\quit 3
\endif
-- passive runtime has no DML betting_worker.operation_authorization
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_authorization','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_authorization','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_authorization','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_authorization','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: passive runtime has no DML betting_worker.operation_authorization'
\quit 3
\endif
SELECT * FROM betting_worker.operation_authorization LIMIT 0;
-- runtime cannot create objects betting_worker
SELECT (NOT has_schema_privilege('dev3_it_5c84548cf006_worker_app','betting_worker','CREATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime cannot create objects betting_worker'
\quit 3
\endif
SELECT installed_rank,version,description,checksum,success FROM betting_worker.flyway_schema_history ORDER BY installed_rank;
-- migration history version betting_worker.1
SELECT (EXISTS(SELECT 1 FROM betting_worker.flyway_schema_history WHERE version='1' AND success) AND NOT EXISTS(SELECT 1 FROM betting_worker.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version betting_worker.1'
\quit 3
\endif
-- migration history version betting_worker.2
SELECT (EXISTS(SELECT 1 FROM betting_worker.flyway_schema_history WHERE version='2' AND success) AND NOT EXISTS(SELECT 1 FROM betting_worker.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version betting_worker.2'
\quit 3
\endif
-- migration history version betting_worker.3
SELECT (EXISTS(SELECT 1 FROM betting_worker.flyway_schema_history WHERE version='3' AND success) AND NOT EXISTS(SELECT 1 FROM betting_worker.flyway_schema_history WHERE NOT success)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migration history version betting_worker.3'
\quit 3
\endif
-- no unexpected migration versions betting_worker
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.flyway_schema_history WHERE version IS NULL OR version NOT IN ('1','2','3'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no unexpected migration versions betting_worker'
\quit 3
\endif
-- Dashboard epoch singleton
SELECT ((SELECT count(*) FROM dashboard.stream_epoch)=1 AND EXISTS(SELECT 1 FROM dashboard.stream_epoch WHERE singleton=TRUE AND epoch IS NOT NULL)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: Dashboard epoch singleton'
\quit 3
\endif
-- Notification fresh cursor singleton
SELECT ((SELECT count(*) FROM notification.cursor_state)=1 AND EXISTS(SELECT 1 FROM notification.cursor_state WHERE singleton=TRUE AND epoch IS NOT NULL AND last_sequence=0)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: Notification fresh cursor singleton'
\quit 3
\endif
-- Control authority singleton without leases
SELECT ((SELECT count(*) FROM betting_control.authorization_authority)=1 AND EXISTS(SELECT 1 FROM betting_control.authorization_authority WHERE singleton=TRUE AND epoch IS NOT NULL)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: Control authority singleton without leases'
\quit 3
\endif
-- concordant initial catalogs
SELECT ((SELECT count(*) FROM dashboard.matches)=1 AND (SELECT count(*) FROM match_state.match_catalog)=1 AND EXISTS(SELECT 1 FROM dashboard.matches d JOIN match_state.match_catalog c USING(match_id) WHERE d.match_id='e2e-5c84548cf006' AND d.home_team_id=c.home_team_id AND d.away_team_id=c.away_team_id AND d.home_team_id='home' AND d.away_team_id='away' AND d.home_name='Atlético Local' AND d.away_name='Deportivo Visitante' AND d.home_score=0 AND d.away_score=0 AND d.var_status='CLEAR' AND d.last_event_id IS NULL AND d.updated_at IS NULL AND c.revision=0 AND c.rules_version=1)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: concordant initial catalogs'
\quit 3
\endif
-- serial ownership dashboard
SELECT (pg_get_serial_sequence('dashboard.inbox','seq')='dashboard.inbox_seq_seq') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: serial ownership dashboard'
\quit 3
\endif
-- monotonic noncycling sequence dashboard
SELECT (EXISTS(SELECT 1 FROM pg_sequence WHERE seqrelid='dashboard.inbox_seq_seq'::regclass AND seqincrement=1 AND seqmin=1 AND NOT seqcycle)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: monotonic noncycling sequence dashboard'
\quit 3
\endif
-- sequence will not reuse existing keys dashboard
SELECT ((SELECT s.last_value >= COALESCE((SELECT max(seq) FROM dashboard.inbox),0) AND (s.is_called OR s.last_value>COALESCE((SELECT max(seq) FROM dashboard.inbox),0)) FROM dashboard.inbox_seq_seq s)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: sequence will not reuse existing keys dashboard'
\quit 3
\endif
SELECT last_value,is_called FROM dashboard.inbox_seq_seq;
-- serial ownership archiver
SELECT (pg_get_serial_sequence('archiver.archived_event','seq')='archiver.archived_event_seq_seq') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: serial ownership archiver'
\quit 3
\endif
-- monotonic noncycling sequence archiver
SELECT (EXISTS(SELECT 1 FROM pg_sequence WHERE seqrelid='archiver.archived_event_seq_seq'::regclass AND seqincrement=1 AND seqmin=1 AND NOT seqcycle)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: monotonic noncycling sequence archiver'
\quit 3
\endif
-- sequence will not reuse existing keys archiver
SELECT ((SELECT s.last_value >= COALESCE((SELECT max(seq) FROM archiver.archived_event),0) AND (s.is_called OR s.last_value>COALESCE((SELECT max(seq) FROM archiver.archived_event),0)) FROM archiver.archived_event_seq_seq s)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: sequence will not reuse existing keys archiver'
\quit 3
\endif
SELECT last_value,is_called FROM archiver.archived_event_seq_seq;
-- fresh baseline empty match_state.accepted_event
SELECT (NOT EXISTS(SELECT 1 FROM match_state.accepted_event)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty match_state.accepted_event'
\quit 3
\endif
-- fresh baseline empty match_state.score_outbox
SELECT (NOT EXISTS(SELECT 1 FROM match_state.score_outbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty match_state.score_outbox'
\quit 3
\endif
-- fresh baseline empty match_state.event_identity
SELECT (NOT EXISTS(SELECT 1 FROM match_state.event_identity)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty match_state.event_identity'
\quit 3
\endif
-- fresh baseline empty match_state.quarantine_case
SELECT (NOT EXISTS(SELECT 1 FROM match_state.quarantine_case)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty match_state.quarantine_case'
\quit 3
\endif
-- fresh baseline empty match_state.input_receipt
SELECT (NOT EXISTS(SELECT 1 FROM match_state.input_receipt)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty match_state.input_receipt'
\quit 3
\endif
-- fresh baseline empty match_state.replay_attempt
SELECT (NOT EXISTS(SELECT 1 FROM match_state.replay_attempt)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty match_state.replay_attempt'
\quit 3
\endif
-- fresh baseline empty match_state.alert_outbox
SELECT (NOT EXISTS(SELECT 1 FROM match_state.alert_outbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty match_state.alert_outbox'
\quit 3
\endif
-- fresh baseline empty dashboard.inbox
SELECT (NOT EXISTS(SELECT 1 FROM dashboard.inbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty dashboard.inbox'
\quit 3
\endif
-- fresh baseline empty notification.inbox
SELECT (NOT EXISTS(SELECT 1 FROM notification.inbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty notification.inbox'
\quit 3
\endif
-- fresh baseline empty notification.alerts
SELECT (NOT EXISTS(SELECT 1 FROM notification.alerts)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty notification.alerts'
\quit 3
\endif
-- fresh baseline empty notification.live_alert_inbox
SELECT (NOT EXISTS(SELECT 1 FROM notification.live_alert_inbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty notification.live_alert_inbox'
\quit 3
\endif
-- fresh baseline empty archiver.archived_event
SELECT (NOT EXISTS(SELECT 1 FROM archiver.archived_event)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty archiver.archived_event'
\quit 3
\endif
-- fresh baseline empty archiver.input_receipt
SELECT (NOT EXISTS(SELECT 1 FROM archiver.input_receipt)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty archiver.input_receipt'
\quit 3
\endif
-- fresh baseline empty betting_control.market_state
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.market_state)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_control.market_state'
\quit 3
\endif
-- fresh baseline empty betting_control.processed_event
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.processed_event)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_control.processed_event'
\quit 3
\endif
-- fresh baseline empty betting_control.pending_review
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.pending_review)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_control.pending_review'
\quit 3
\endif
-- fresh baseline empty betting_control.input_receipt
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.input_receipt)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_control.input_receipt'
\quit 3
\endif
-- fresh baseline empty betting_control.closure_command
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.closure_command)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_control.closure_command'
\quit 3
\endif
-- fresh baseline empty betting_control.transition_history
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.transition_history)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_control.transition_history'
\quit 3
\endif
-- fresh baseline empty betting_control.suspension_outbox
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.suspension_outbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_control.suspension_outbox'
\quit 3
\endif
-- fresh baseline empty betting_control.academic_outbox
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.academic_outbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_control.academic_outbox'
\quit 3
\endif
-- fresh baseline empty betting_control.authorization_lease
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.authorization_lease)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_control.authorization_lease'
\quit 3
\endif
-- fresh baseline empty betting_worker.market_guard
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.market_guard)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_worker.market_guard'
\quit 3
\endif
-- fresh baseline empty betting_worker.command_inbox
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.command_inbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_worker.command_inbox'
\quit 3
\endif
-- fresh baseline empty betting_worker.operation
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.operation)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_worker.operation'
\quit 3
\endif
-- fresh baseline empty betting_worker.operation_transition
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.operation_transition)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_worker.operation_transition'
\quit 3
\endif
-- fresh baseline empty betting_worker.operation_evidence
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.operation_evidence)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_worker.operation_evidence'
\quit 3
\endif
-- fresh baseline empty betting_worker.message_audit
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.message_audit)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_worker.message_audit'
\quit 3
\endif
-- fresh baseline empty betting_worker.academic_command
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.academic_command)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_worker.academic_command'
\quit 3
\endif
-- fresh baseline empty betting_worker.academic_delivery
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.academic_delivery)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_worker.academic_delivery'
\quit 3
\endif
-- fresh baseline empty betting_worker.operation_authorization
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.operation_authorization)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh baseline empty betting_worker.operation_authorization'
\quit 3
\endif
-- no runtime authority writes betting_control.authorization_authority
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_authority','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_authority','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_authority','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_authority','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no runtime authority writes betting_control.authorization_authority'
\quit 3
\endif
-- no runtime authority writes betting_control.authorization_lease
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_lease','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_lease','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_lease','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_control_app','betting_control.authorization_lease','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no runtime authority writes betting_control.authorization_lease'
\quit 3
\endif
-- no runtime authority writes betting_worker.operation_authorization
SELECT (NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_authorization','INSERT') AND NOT has_any_column_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_authorization','UPDATE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_authorization','DELETE') AND NOT has_table_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.operation_authorization','TRUNCATE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: no runtime authority writes betting_worker.operation_authorization'
\quit 3
\endif
-- runtime authority functions remain denied
SELECT (NOT has_function_privilege('dev3_it_5c84548cf006_worker_app','betting_worker.record_atomic_authorization(text,text,bigint,uuid,uuid)','EXECUTE') AND NOT has_function_privilege('dev3_it_5c84548cf006_worker_app','betting_control.lock_market_authority(text)','EXECUTE')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime authority functions remain denied'
\quit 3
\endif
-- SECURITY DEFINER owner and search path lock_market_authority
SELECT ((SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='betting_control' AND p.proname='lock_market_authority')=1 AND EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='betting_control' AND p.proname='lock_market_authority' AND p.prosecdef AND pg_get_userbyid(p.proowner)='dev3_it_5c84548cf006_control_owner' AND p.proconfig @> ARRAY['search_path=pg_catalog'])) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: SECURITY DEFINER owner and search path lock_market_authority'
\quit 3
\endif
-- SECURITY DEFINER owner and search path record_atomic_authorization
SELECT ((SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='betting_worker' AND p.proname='record_atomic_authorization')=1 AND EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='betting_worker' AND p.proname='record_atomic_authorization' AND p.prosecdef AND pg_get_userbyid(p.proowner)='dev3_it_5c84548cf006_worker_owner' AND p.proconfig @> ARRAY['search_path=pg_catalog'])) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: SECURITY DEFINER owner and search path record_atomic_authorization'
\quit 3
\endif
-- SECURITY DEFINER owner and search path require_transactional_authorization
SELECT ((SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='betting_worker' AND p.proname='require_transactional_authorization')=1 AND EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='betting_worker' AND p.proname='require_transactional_authorization' AND p.prosecdef AND pg_get_userbyid(p.proowner)='dev3_it_5c84548cf006_worker_owner' AND p.proconfig @> ARRAY['search_path=pg_catalog'])) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: SECURITY DEFINER owner and search path require_transactional_authorization'
\quit 3
\endif
-- SECURITY DEFINER owner and search path require_accepted_authorization
SELECT ((SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='betting_worker' AND p.proname='require_accepted_authorization')=1 AND EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='betting_worker' AND p.proname='require_accepted_authorization' AND p.prosecdef AND pg_get_userbyid(p.proowner)='dev3_it_5c84548cf006_worker_owner' AND p.proconfig @> ARRAY['search_path=pg_catalog'])) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: SECURITY DEFINER owner and search path require_accepted_authorization'
\quit 3
\endif
-- enabled deferred trigger accepted_requires_atomic_authorization
SELECT (EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='betting_worker.operation'::regclass AND tgname='accepted_requires_atomic_authorization' AND tgdeferrable AND tginitdeferred AND tgenabled='O')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: enabled deferred trigger accepted_requires_atomic_authorization'
\quit 3
\endif
-- enabled deferred trigger authorization_requires_accepted_operation
SELECT (EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='betting_worker.operation_authorization'::regclass AND tgname='authorization_requires_accepted_operation' AND tgdeferrable AND tginitdeferred AND tgenabled='O')) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: enabled deferred trigger authorization_requires_accepted_operation'
\quit 3
\endif
SELECT n.nspname,p.proname,pg_get_userbyid(p.proowner) AS owner,p.prosecdef,p.proconfig FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('betting_control','betting_worker');
SELECT conname,condeferrable,condeferred FROM pg_constraint WHERE conrelid IN ('betting_worker.operation'::regclass,'betting_worker.operation_authorization'::regclass);
