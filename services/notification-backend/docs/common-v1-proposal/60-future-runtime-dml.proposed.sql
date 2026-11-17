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
-- NOT FOR PASSIVE STARTUP. Separate operator authorization for a BLOCKED-only V1 rehearsal.
\if :{?runtime_dml_approved_run}
\else
\echo 'Separate runtime DML approval missing'
\quit 3
\endif
-- separate BLOCKED-only DML scope
SELECT (:'runtime_dml_approved_run'='blocked-only:5c84548cf006') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: separate BLOCKED-only DML scope'
\quit 3
\endif
BEGIN;
GRANT INSERT ON match_state.accepted_event,match_state.score_outbox,match_state.event_identity,match_state.quarantine_case,match_state.input_receipt,match_state.alert_outbox TO dev3_it_5c84548cf006_match_state_app;
GRANT UPDATE ON match_state.match_catalog,match_state.score_outbox,match_state.event_identity,match_state.input_receipt,match_state.alert_outbox TO dev3_it_5c84548cf006_match_state_app;
GRANT INSERT ON dashboard.inbox TO dev3_it_5c84548cf006_dashboard_app;
GRANT UPDATE(home_score,away_score,var_status,last_event_id,updated_at) ON dashboard.matches TO dev3_it_5c84548cf006_dashboard_app;
GRANT USAGE ON SEQUENCE dashboard.inbox_seq_seq TO dev3_it_5c84548cf006_dashboard_app;
GRANT INSERT ON notification.inbox,notification.alerts,notification.live_alert_inbox TO dev3_it_5c84548cf006_notification_app;
GRANT UPDATE(last_sequence) ON notification.cursor_state TO dev3_it_5c84548cf006_notification_app;
GRANT INSERT ON archiver.archived_event,archiver.input_receipt TO dev3_it_5c84548cf006_archive_app;
GRANT UPDATE ON archiver.input_receipt TO dev3_it_5c84548cf006_archive_app;
GRANT USAGE ON SEQUENCE archiver.archived_event_seq_seq TO dev3_it_5c84548cf006_archive_app;
GRANT INSERT ON betting_control.market_state,betting_control.processed_event,betting_control.pending_review,betting_control.input_receipt,betting_control.closure_command,betting_control.transition_history,betting_control.suspension_outbox TO dev3_it_5c84548cf006_control_app;
GRANT UPDATE ON betting_control.market_state,betting_control.pending_review,betting_control.input_receipt,betting_control.suspension_outbox TO dev3_it_5c84548cf006_control_app;
GRANT INSERT ON betting_worker.market_guard,betting_worker.command_inbox,betting_worker.operation,betting_worker.operation_transition,betting_worker.operation_evidence,betting_worker.message_audit TO dev3_it_5c84548cf006_worker_app;
GRANT UPDATE ON betting_worker.market_guard,betting_worker.command_inbox,betting_worker.operation,betting_worker.operation_evidence TO dev3_it_5c84548cf006_worker_app;
COMMIT;
-- No grants on authorization_lease/authorization_authority or operation_authorization writes.
-- No runtime EXECUTE on authority functions. Acceptance OFF and authorization disabled remain mandatory.
