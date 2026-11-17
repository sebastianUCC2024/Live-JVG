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
BEGIN;
REVOKE ALL ON ALL TABLES IN SCHEMA match_state FROM dev3_it_5c84548cf006_match_state_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA match_state FROM dev3_it_5c84548cf006_match_state_app;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA match_state FROM dev3_it_5c84548cf006_match_state_app;
REVOKE CREATE ON SCHEMA match_state FROM dev3_it_5c84548cf006_match_state_app;
GRANT USAGE ON SCHEMA match_state TO dev3_it_5c84548cf006_match_state_app;
GRANT SELECT ON match_state.match_catalog,match_state.accepted_event,match_state.score_outbox,match_state.event_identity,match_state.quarantine_case,match_state.input_receipt,match_state.replay_attempt,match_state.alert_outbox TO dev3_it_5c84548cf006_match_state_app;
REVOKE ALL ON ALL TABLES IN SCHEMA dashboard FROM dev3_it_5c84548cf006_dashboard_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA dashboard FROM dev3_it_5c84548cf006_dashboard_app;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA dashboard FROM dev3_it_5c84548cf006_dashboard_app;
REVOKE CREATE ON SCHEMA dashboard FROM dev3_it_5c84548cf006_dashboard_app;
GRANT USAGE ON SCHEMA dashboard TO dev3_it_5c84548cf006_dashboard_app;
GRANT SELECT ON dashboard.matches,dashboard.inbox,dashboard.stream_epoch TO dev3_it_5c84548cf006_dashboard_app;
REVOKE ALL ON ALL TABLES IN SCHEMA notification FROM dev3_it_5c84548cf006_notification_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA notification FROM dev3_it_5c84548cf006_notification_app;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA notification FROM dev3_it_5c84548cf006_notification_app;
REVOKE CREATE ON SCHEMA notification FROM dev3_it_5c84548cf006_notification_app;
GRANT USAGE ON SCHEMA notification TO dev3_it_5c84548cf006_notification_app;
GRANT SELECT ON notification.cursor_state,notification.inbox,notification.alerts,notification.live_alert_inbox TO dev3_it_5c84548cf006_notification_app;
REVOKE ALL ON ALL TABLES IN SCHEMA archiver FROM dev3_it_5c84548cf006_archive_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA archiver FROM dev3_it_5c84548cf006_archive_app;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA archiver FROM dev3_it_5c84548cf006_archive_app;
REVOKE CREATE ON SCHEMA archiver FROM dev3_it_5c84548cf006_archive_app;
GRANT USAGE ON SCHEMA archiver TO dev3_it_5c84548cf006_archive_app;
GRANT SELECT ON archiver.archived_event,archiver.input_receipt TO dev3_it_5c84548cf006_archive_app;
REVOKE ALL ON ALL TABLES IN SCHEMA betting_control FROM dev3_it_5c84548cf006_control_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA betting_control FROM dev3_it_5c84548cf006_control_app;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA betting_control FROM dev3_it_5c84548cf006_control_app;
REVOKE CREATE ON SCHEMA betting_control FROM dev3_it_5c84548cf006_control_app;
GRANT USAGE ON SCHEMA betting_control TO dev3_it_5c84548cf006_control_app;
GRANT SELECT ON betting_control.market_state,betting_control.processed_event,betting_control.pending_review,betting_control.input_receipt,betting_control.closure_command,betting_control.transition_history,betting_control.suspension_outbox,betting_control.authorization_authority,betting_control.authorization_lease TO dev3_it_5c84548cf006_control_app;
REVOKE ALL ON ALL TABLES IN SCHEMA betting_worker FROM dev3_it_5c84548cf006_worker_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA betting_worker FROM dev3_it_5c84548cf006_worker_app;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA betting_worker FROM dev3_it_5c84548cf006_worker_app;
REVOKE CREATE ON SCHEMA betting_worker FROM dev3_it_5c84548cf006_worker_app;
GRANT USAGE ON SCHEMA betting_worker TO dev3_it_5c84548cf006_worker_app;
GRANT SELECT ON betting_worker.market_guard,betting_worker.command_inbox,betting_worker.operation,betting_worker.operation_transition,betting_worker.operation_evidence,betting_worker.message_audit,betting_worker.operation_authorization TO dev3_it_5c84548cf006_worker_app;
COMMIT;
-- Runtime cannot issue leases, forge authorization, migrate or mutate passive fixtures.
