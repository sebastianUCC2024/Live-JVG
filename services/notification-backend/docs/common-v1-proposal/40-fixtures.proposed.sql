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
INSERT INTO match_state.match_catalog(match_id,home_team_id,away_team_id) VALUES ('e2e-5c84548cf006','home','away') ON CONFLICT DO NOTHING;
INSERT INTO dashboard.matches(match_id,home_team_id,away_team_id,home_name,away_name) VALUES ('e2e-5c84548cf006','home','away','Atlético Local','Deportivo Visitante') ON CONFLICT DO NOTHING;
-- fresh concordant Match State catalog
SELECT ((SELECT count(*) FROM match_state.match_catalog)=1 AND EXISTS(SELECT 1 FROM match_state.match_catalog WHERE match_id='e2e-5c84548cf006' AND home_team_id='home' AND away_team_id='away' AND revision=0 AND rules_version=1)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh concordant Match State catalog'
\quit 3
\endif
-- fresh concordant Dashboard catalog
SELECT ((SELECT count(*) FROM dashboard.matches)=1 AND EXISTS(SELECT 1 FROM dashboard.matches WHERE match_id='e2e-5c84548cf006' AND home_team_id='home' AND away_team_id='away' AND home_name='Atlético Local' AND away_name='Deportivo Visitante' AND home_score=0 AND away_score=0 AND var_status='CLEAR' AND last_event_id IS NULL AND updated_at IS NULL)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: fresh concordant Dashboard catalog'
\quit 3
\endif
-- empty fresh fixture match_state.accepted_event
SELECT (NOT EXISTS(SELECT 1 FROM match_state.accepted_event)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture match_state.accepted_event'
\quit 3
\endif
-- empty fresh fixture match_state.score_outbox
SELECT (NOT EXISTS(SELECT 1 FROM match_state.score_outbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture match_state.score_outbox'
\quit 3
\endif
-- empty fresh fixture match_state.event_identity
SELECT (NOT EXISTS(SELECT 1 FROM match_state.event_identity)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture match_state.event_identity'
\quit 3
\endif
-- empty fresh fixture match_state.quarantine_case
SELECT (NOT EXISTS(SELECT 1 FROM match_state.quarantine_case)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture match_state.quarantine_case'
\quit 3
\endif
-- empty fresh fixture match_state.input_receipt
SELECT (NOT EXISTS(SELECT 1 FROM match_state.input_receipt)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture match_state.input_receipt'
\quit 3
\endif
-- empty fresh fixture match_state.replay_attempt
SELECT (NOT EXISTS(SELECT 1 FROM match_state.replay_attempt)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture match_state.replay_attempt'
\quit 3
\endif
-- empty fresh fixture match_state.alert_outbox
SELECT (NOT EXISTS(SELECT 1 FROM match_state.alert_outbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture match_state.alert_outbox'
\quit 3
\endif
-- empty fresh fixture dashboard.inbox
SELECT (NOT EXISTS(SELECT 1 FROM dashboard.inbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture dashboard.inbox'
\quit 3
\endif
-- empty fresh fixture notification.inbox
SELECT (NOT EXISTS(SELECT 1 FROM notification.inbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture notification.inbox'
\quit 3
\endif
-- empty fresh fixture notification.alerts
SELECT (NOT EXISTS(SELECT 1 FROM notification.alerts)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture notification.alerts'
\quit 3
\endif
-- empty fresh fixture notification.live_alert_inbox
SELECT (NOT EXISTS(SELECT 1 FROM notification.live_alert_inbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture notification.live_alert_inbox'
\quit 3
\endif
-- empty fresh fixture archiver.archived_event
SELECT (NOT EXISTS(SELECT 1 FROM archiver.archived_event)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture archiver.archived_event'
\quit 3
\endif
-- empty fresh fixture archiver.input_receipt
SELECT (NOT EXISTS(SELECT 1 FROM archiver.input_receipt)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture archiver.input_receipt'
\quit 3
\endif
-- empty fresh fixture betting_control.market_state
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.market_state)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_control.market_state'
\quit 3
\endif
-- empty fresh fixture betting_control.processed_event
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.processed_event)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_control.processed_event'
\quit 3
\endif
-- empty fresh fixture betting_control.pending_review
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.pending_review)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_control.pending_review'
\quit 3
\endif
-- empty fresh fixture betting_control.input_receipt
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.input_receipt)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_control.input_receipt'
\quit 3
\endif
-- empty fresh fixture betting_control.closure_command
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.closure_command)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_control.closure_command'
\quit 3
\endif
-- empty fresh fixture betting_control.transition_history
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.transition_history)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_control.transition_history'
\quit 3
\endif
-- empty fresh fixture betting_control.suspension_outbox
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.suspension_outbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_control.suspension_outbox'
\quit 3
\endif
-- empty fresh fixture betting_control.academic_outbox
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.academic_outbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_control.academic_outbox'
\quit 3
\endif
-- empty fresh fixture betting_control.authorization_lease
SELECT (NOT EXISTS(SELECT 1 FROM betting_control.authorization_lease)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_control.authorization_lease'
\quit 3
\endif
-- empty fresh fixture betting_worker.market_guard
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.market_guard)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_worker.market_guard'
\quit 3
\endif
-- empty fresh fixture betting_worker.command_inbox
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.command_inbox)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_worker.command_inbox'
\quit 3
\endif
-- empty fresh fixture betting_worker.operation
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.operation)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_worker.operation'
\quit 3
\endif
-- empty fresh fixture betting_worker.operation_transition
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.operation_transition)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_worker.operation_transition'
\quit 3
\endif
-- empty fresh fixture betting_worker.operation_evidence
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.operation_evidence)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_worker.operation_evidence'
\quit 3
\endif
-- empty fresh fixture betting_worker.message_audit
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.message_audit)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_worker.message_audit'
\quit 3
\endif
-- empty fresh fixture betting_worker.academic_command
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.academic_command)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_worker.academic_command'
\quit 3
\endif
-- empty fresh fixture betting_worker.academic_delivery
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.academic_delivery)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_worker.academic_delivery'
\quit 3
\endif
-- empty fresh fixture betting_worker.operation_authorization
SELECT (NOT EXISTS(SELECT 1 FROM betting_worker.operation_authorization)) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: empty fresh fixture betting_worker.operation_authorization'
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
COMMIT;
-- Existing epochs are preserved. Never seed events, OPEN markets, leases or accepted operations.
