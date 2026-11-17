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
-- new empty database or exact previously provisioned run
SELECT ((obj_description((SELECT oid FROM pg_database WHERE datname=current_database()),'pg_database') IS NOT DISTINCT FROM ('live-jvg/common-v1/5c84548cf006/' || :'expected_system_identifier')) OR (obj_description((SELECT oid FROM pg_database WHERE datname=current_database()),'pg_database') IS NULL AND NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema') AND NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema') AND NOT EXISTS(SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: new empty database or exact previously provisioned run'
\quit 3
\endif
-- unmarked fresh database cannot adopt existing run roles
SELECT (obj_description((SELECT oid FROM pg_database WHERE datname=current_database()),'pg_database') IS NOT NULL OR NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname IN ('dev3_it_5c84548cf006_database_owner','dev3_it_5c84548cf006_match_state_owner','dev3_it_5c84548cf006_match_state_migrator','dev3_it_5c84548cf006_match_state_app','dev3_it_5c84548cf006_dashboard_owner','dev3_it_5c84548cf006_dashboard_migrator','dev3_it_5c84548cf006_dashboard_app','dev3_it_5c84548cf006_notification_owner','dev3_it_5c84548cf006_notification_migrator','dev3_it_5c84548cf006_notification_app','dev3_it_5c84548cf006_archive_owner','dev3_it_5c84548cf006_archive_migrator','dev3_it_5c84548cf006_archive_app','dev3_it_5c84548cf006_control_owner','dev3_it_5c84548cf006_control_migrator','dev3_it_5c84548cf006_control_app','dev3_it_5c84548cf006_worker_owner','dev3_it_5c84548cf006_worker_migrator','dev3_it_5c84548cf006_worker_app'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: unmarked fresh database cannot adopt existing run roles'
\quit 3
\endif
-- DBA and approved database owner required
SELECT ((SELECT rolsuper FROM pg_roles WHERE rolname=current_user) AND (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) = CASE WHEN obj_description((SELECT oid FROM pg_database WHERE datname=current_database()),'pg_database') IS NULL THEN current_user::text ELSE 'dev3_it_5c84548cf006_database_owner' END) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: DBA and approved database owner required'
\quit 3
\endif
BEGIN;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_database_owner') THEN
  CREATE ROLE dev3_it_5c84548cf006_database_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_database_owner' AND rolcanlogin=FALSE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
-- owner cannot assume other roles dev3_it_5c84548cf006_database_owner
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_database_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: owner cannot assume other roles dev3_it_5c84548cf006_database_owner'
\quit 3
\endif
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_owner') THEN
  CREATE ROLE dev3_it_5c84548cf006_match_state_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_owner' AND rolcanlogin=FALSE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
-- owner cannot assume other roles dev3_it_5c84548cf006_match_state_owner
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: owner cannot assume other roles dev3_it_5c84548cf006_match_state_owner'
\quit 3
\endif
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_migrator') THEN
  CREATE ROLE dev3_it_5c84548cf006_match_state_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_migrator' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_app') THEN
  CREATE ROLE dev3_it_5c84548cf006_match_state_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_app' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_owner') THEN
  CREATE ROLE dev3_it_5c84548cf006_dashboard_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_owner' AND rolcanlogin=FALSE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
-- owner cannot assume other roles dev3_it_5c84548cf006_dashboard_owner
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: owner cannot assume other roles dev3_it_5c84548cf006_dashboard_owner'
\quit 3
\endif
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_migrator') THEN
  CREATE ROLE dev3_it_5c84548cf006_dashboard_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_migrator' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_app') THEN
  CREATE ROLE dev3_it_5c84548cf006_dashboard_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_app' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_owner') THEN
  CREATE ROLE dev3_it_5c84548cf006_notification_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_owner' AND rolcanlogin=FALSE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
-- owner cannot assume other roles dev3_it_5c84548cf006_notification_owner
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: owner cannot assume other roles dev3_it_5c84548cf006_notification_owner'
\quit 3
\endif
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_migrator') THEN
  CREATE ROLE dev3_it_5c84548cf006_notification_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_migrator' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_app') THEN
  CREATE ROLE dev3_it_5c84548cf006_notification_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_app' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_owner') THEN
  CREATE ROLE dev3_it_5c84548cf006_archive_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_owner' AND rolcanlogin=FALSE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
-- owner cannot assume other roles dev3_it_5c84548cf006_archive_owner
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: owner cannot assume other roles dev3_it_5c84548cf006_archive_owner'
\quit 3
\endif
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_migrator') THEN
  CREATE ROLE dev3_it_5c84548cf006_archive_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_migrator' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_app') THEN
  CREATE ROLE dev3_it_5c84548cf006_archive_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_app' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_owner') THEN
  CREATE ROLE dev3_it_5c84548cf006_control_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_owner' AND rolcanlogin=FALSE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
-- owner cannot assume other roles dev3_it_5c84548cf006_control_owner
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: owner cannot assume other roles dev3_it_5c84548cf006_control_owner'
\quit 3
\endif
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_migrator') THEN
  CREATE ROLE dev3_it_5c84548cf006_control_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_migrator' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_app') THEN
  CREATE ROLE dev3_it_5c84548cf006_control_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_app' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_owner') THEN
  CREATE ROLE dev3_it_5c84548cf006_worker_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_owner' AND rolcanlogin=FALSE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
-- owner cannot assume other roles dev3_it_5c84548cf006_worker_owner
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: owner cannot assume other roles dev3_it_5c84548cf006_worker_owner'
\quit 3
\endif
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_migrator') THEN
  CREATE ROLE dev3_it_5c84548cf006_worker_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_migrator' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
DO $role$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_app') THEN
  CREATE ROLE dev3_it_5c84548cf006_worker_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_app' AND rolcanlogin=TRUE AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) THEN
   RAISE EXCEPTION 'Existing role attributes differ; refuse automatic repair';
  END IF;
 END IF;
END $role$;
ALTER DATABASE live_jvg OWNER TO dev3_it_5c84548cf006_database_owner;
REVOKE ALL ON DATABASE live_jvg FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS match_state AUTHORIZATION dev3_it_5c84548cf006_match_state_owner;
-- schema owner matches match_state
SELECT ((SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='match_state')='dev3_it_5c84548cf006_match_state_owner') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: schema owner matches match_state'
\quit 3
\endif
GRANT dev3_it_5c84548cf006_match_state_owner TO dev3_it_5c84548cf006_match_state_migrator;
GRANT USAGE ON SCHEMA match_state TO dev3_it_5c84548cf006_match_state_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE dev3_it_5c84548cf006_match_state_owner IN SCHEMA match_state REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- runtime has no role memberships match_state
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_app'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime has no role memberships match_state'
\quit 3
\endif
-- migrator membership limited to its owner match_state
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_migrator') AND roleid<>(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_match_state_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migrator membership limited to its owner match_state'
\quit 3
\endif
CREATE SCHEMA IF NOT EXISTS dashboard AUTHORIZATION dev3_it_5c84548cf006_dashboard_owner;
-- schema owner matches dashboard
SELECT ((SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='dashboard')='dev3_it_5c84548cf006_dashboard_owner') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: schema owner matches dashboard'
\quit 3
\endif
GRANT dev3_it_5c84548cf006_dashboard_owner TO dev3_it_5c84548cf006_dashboard_migrator;
GRANT USAGE ON SCHEMA dashboard TO dev3_it_5c84548cf006_dashboard_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE dev3_it_5c84548cf006_dashboard_owner IN SCHEMA dashboard REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- runtime has no role memberships dashboard
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_app'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime has no role memberships dashboard'
\quit 3
\endif
-- migrator membership limited to its owner dashboard
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_migrator') AND roleid<>(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_dashboard_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migrator membership limited to its owner dashboard'
\quit 3
\endif
CREATE SCHEMA IF NOT EXISTS notification AUTHORIZATION dev3_it_5c84548cf006_notification_owner;
-- schema owner matches notification
SELECT ((SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='notification')='dev3_it_5c84548cf006_notification_owner') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: schema owner matches notification'
\quit 3
\endif
GRANT dev3_it_5c84548cf006_notification_owner TO dev3_it_5c84548cf006_notification_migrator;
GRANT USAGE ON SCHEMA notification TO dev3_it_5c84548cf006_notification_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE dev3_it_5c84548cf006_notification_owner IN SCHEMA notification REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- runtime has no role memberships notification
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_app'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime has no role memberships notification'
\quit 3
\endif
-- migrator membership limited to its owner notification
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_migrator') AND roleid<>(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_notification_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migrator membership limited to its owner notification'
\quit 3
\endif
CREATE SCHEMA IF NOT EXISTS archiver AUTHORIZATION dev3_it_5c84548cf006_archive_owner;
-- schema owner matches archiver
SELECT ((SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='archiver')='dev3_it_5c84548cf006_archive_owner') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: schema owner matches archiver'
\quit 3
\endif
GRANT dev3_it_5c84548cf006_archive_owner TO dev3_it_5c84548cf006_archive_migrator;
GRANT USAGE ON SCHEMA archiver TO dev3_it_5c84548cf006_archive_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE dev3_it_5c84548cf006_archive_owner IN SCHEMA archiver REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- runtime has no role memberships archiver
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_app'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime has no role memberships archiver'
\quit 3
\endif
-- migrator membership limited to its owner archiver
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_migrator') AND roleid<>(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_archive_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migrator membership limited to its owner archiver'
\quit 3
\endif
CREATE SCHEMA IF NOT EXISTS betting_control AUTHORIZATION dev3_it_5c84548cf006_control_owner;
-- schema owner matches betting_control
SELECT ((SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='betting_control')='dev3_it_5c84548cf006_control_owner') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: schema owner matches betting_control'
\quit 3
\endif
GRANT dev3_it_5c84548cf006_control_owner TO dev3_it_5c84548cf006_control_migrator;
GRANT USAGE ON SCHEMA betting_control TO dev3_it_5c84548cf006_control_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE dev3_it_5c84548cf006_control_owner IN SCHEMA betting_control REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- runtime has no role memberships betting_control
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_app'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime has no role memberships betting_control'
\quit 3
\endif
-- migrator membership limited to its owner betting_control
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_migrator') AND roleid<>(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_control_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migrator membership limited to its owner betting_control'
\quit 3
\endif
CREATE SCHEMA IF NOT EXISTS betting_worker AUTHORIZATION dev3_it_5c84548cf006_worker_owner;
-- schema owner matches betting_worker
SELECT ((SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='betting_worker')='dev3_it_5c84548cf006_worker_owner') AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: schema owner matches betting_worker'
\quit 3
\endif
GRANT dev3_it_5c84548cf006_worker_owner TO dev3_it_5c84548cf006_worker_migrator;
GRANT USAGE ON SCHEMA betting_worker TO dev3_it_5c84548cf006_worker_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE dev3_it_5c84548cf006_worker_owner IN SCHEMA betting_worker REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- runtime has no role memberships betting_worker
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_app'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: runtime has no role memberships betting_worker'
\quit 3
\endif
-- migrator membership limited to its owner betting_worker
SELECT (NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_migrator') AND roleid<>(SELECT oid FROM pg_roles WHERE rolname='dev3_it_5c84548cf006_worker_owner'))) AS gate_ok \gset
\if :gate_ok
\else
\echo 'REFUSED: migrator membership limited to its owner betting_worker'
\quit 3
\endif
GRANT CONNECT ON DATABASE live_jvg TO dev3_it_5c84548cf006_match_state_migrator,dev3_it_5c84548cf006_match_state_app,dev3_it_5c84548cf006_dashboard_migrator,dev3_it_5c84548cf006_dashboard_app,dev3_it_5c84548cf006_notification_migrator,dev3_it_5c84548cf006_notification_app,dev3_it_5c84548cf006_archive_migrator,dev3_it_5c84548cf006_archive_app,dev3_it_5c84548cf006_control_migrator,dev3_it_5c84548cf006_control_app,dev3_it_5c84548cf006_worker_migrator,dev3_it_5c84548cf006_worker_app;
SELECT format('COMMENT ON DATABASE live_jvg IS %L', 'live-jvg/common-v1/5c84548cf006/' || :'expected_system_identifier') \gexec
COMMIT;
-- Set LOGIN credentials privately, outside Git. No passwords were generated.
