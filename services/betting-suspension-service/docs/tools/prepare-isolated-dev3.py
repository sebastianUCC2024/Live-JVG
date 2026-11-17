"""Offline SQL/topology plan only. Never connects or executes generated statements."""
import argparse
import json
import re


def plan(token):
    if not re.fullmatch(r'[a-f0-9]{12}', token):
        raise ValueError('Use twelve lowercase hex digits for NEW resources')
    base = 'dev3_it_' + token
    roles = {kind: base + '_' + kind for kind in ('control_owner', 'worker_owner', 'control_migrator',
                                               'worker_migrator', 'control_app', 'worker_app')}
    db = base + '_authority'
    pre = ['\\set ON_ERROR_STOP on', '-- Requires separately authorized NEW isolated server; admin connection.',
           "SELECT current_setting('server_version_num')::int BETWEEN 170000 AND 179999 AS pg17 \\gset",
           '\\if :pg17', '\\else', '\\quit 3', '\\endif']
    # Abort before creating anything if any of these names already exists. No IF NOT EXISTS/reuse.
    names = ','.join("'" + name + "'" for name in roles.values())
    pre += [f"SELECT NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='{db}') AND NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname IN ({names})) AS fresh \\gset",
            '\\if :fresh', '\\else', '\\quit 3', '\\endif']
    for kind, name in roles.items():
        pre.append(f"CREATE ROLE {name} {'NOLOGIN' if kind.endswith('owner') else 'LOGIN'} NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;")
    pre += [f"GRANT {roles['control_owner']} TO {roles['control_migrator']};",
            f"GRANT {roles['worker_owner']} TO {roles['worker_migrator']};",
            f'CREATE DATABASE {db};', f'\\connect {db}',
            f'REVOKE ALL ON DATABASE {db} FROM PUBLIC;',
            f"GRANT CONNECT ON DATABASE {db} TO {','.join(roles.values())};",
            'REVOKE CREATE ON SCHEMA public FROM PUBLIC;',
            f"CREATE SCHEMA betting_control AUTHORIZATION {roles['control_owner']};",
            f"CREATE SCHEMA betting_worker AUTHORIZATION {roles['worker_owner']};",
            '-- Set passwords privately; none are generated. Flyway must SET ROLE to each owner.',
            '-- STOP: migrate CONTROL V1/V2, then WORKER V1/V2 using separate schema histories.']
    c, w, wo = roles['control_app'], roles['worker_app'], roles['worker_owner']
    post = ['\\set ON_ERROR_STOP on', f'\\connect {db}',
            f'GRANT USAGE ON SCHEMA betting_control TO {c},{w},{wo};',
            f'GRANT USAGE ON SCHEMA betting_worker TO {w};',
            f'GRANT SELECT,INSERT,UPDATE ON betting_control.market_state,betting_control.processed_event,betting_control.pending_review,betting_control.input_receipt,betting_control.closure_command,betting_control.transition_history,betting_control.suspension_outbox TO {c};',
            f'GRANT SELECT ON betting_control.authorization_authority,betting_control.authorization_lease TO {c};',
            f'GRANT EXECUTE ON FUNCTION betting_control.lock_market_authority(text) TO {w},{wo};',
            f'GRANT SELECT,INSERT,UPDATE ON betting_worker.market_guard,betting_worker.command_inbox,betting_worker.operation,betting_worker.operation_transition,betting_worker.operation_evidence,betting_worker.message_audit TO {w};',
            f'GRANT EXECUTE ON FUNCTION betting_worker.record_atomic_authorization(text,text,bigint,uuid,uuid) TO {w};',
            f'GRANT SELECT ON betting_worker.operation_authorization TO {w};',
            '-- Read-only startup checks allowed; no runtime writes to authorization tables or lease issuer.',
            "SELECT current_database(),epoch FROM betting_control.authorization_authority;",
            "SELECT n.nspname,p.proname,p.prosecdef,p.proconfig,pg_get_userbyid(p.proowner) AS owner FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('betting_control','betting_worker');",
            "SELECT conname,condeferrable,condeferred FROM pg_constraint WHERE conrelid IN ('betting_worker.operation'::regclass,'betting_worker.operation_authorization'::regclass);",
            f"SELECT has_table_privilege('{w}','betting_worker.operation_authorization','INSERT') AS must_be_false,has_function_privilege('{w}','betting_worker.record_atomic_authorization(text,text,bigint,uuid,uuid)','EXECUTE') AS must_be_true;"]
    return dict(executed=False, database=db, roles=roles, pre_migration_sql='\n'.join(pre)+'\n',
                post_migration_sql='\n'.join(post)+'\n',
                temporary_brokers=dict(topic=base+'_events',group_archive=base+'_archive',group_control=base+'_control',
                                       exchange=base+'_commands',queue=base+'_worker',routing=base+'.command'),
                flags=dict(consumers=False,publication=False,acceptance=False,authority='disabled'),
                requirements=['New server/project explicitly authorized; never the historical laboratory',
                              'Supply private migrator/runtime authentication outside Git',
                              'Review owners, inherited privileges, SECURITY DEFINER paths and deferred constraints',
                              'Runtime cannot migrate or issue leases; approval of protocol/topology remains separate'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--token', required=True)
    print(json.dumps(plan(parser.parse_args().token), indent=2))
