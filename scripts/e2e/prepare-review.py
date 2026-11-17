"""Offline resource manifest derived from DEV 3's committed generator. No connections/DDL."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'services/betting-suspension-service/docs/tools/prepare-isolated-dev3.py'


def prepare(run, layout='common-v1'):
    if not re.fullmatch(r'[a-f0-9]{12}', run):
        raise ValueError('Twelve lowercase hex digits required')
    spec = importlib.util.spec_from_file_location('dev3_offline_proposal', SOURCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    source = module.plan(run)
    if layout not in {'common-v1', 'standalone'}:
        raise ValueError('Choose common-v1 or standalone explicitly')
    broker = source['temporary_brokers']
    prefix = 'dev3_it_' + run
    roles = dict(source['roles'])
    roles.update(archive_app=prefix+'_archive_app', match_state_app=prefix+'_match_state_app',
                 dashboard_app=prefix+'_dashboard_app', notification_app=prefix+'_notification_app')
    roles.update(archive_owner=prefix+'_archive_owner', archive_migrator=prefix+'_archive_migrator')
    common = layout == 'common-v1'
    return dict(status='PROPOSED_NOT_APPROVED', executed=False, run=run, layout=layout, project='live-jvg-e2e-'+run,
                resources=dict(database='live_jvg' if common else source['database'],
                               archiver_database='live_jvg' if common else prefix+'_archive',
                               topic='match_events' if common else broker['topic'], exchange=broker['exchange'], queue=broker['queue'],
                               routing=broker['routing'], vhost='/' if common else prefix),
                groups=dict(archive=broker['group_archive'], control=broker['group_control'], match_state=prefix+'_match_state'),
                source_ids=dict(kafka='live-jvg-e2e-'+run+'/kafka-log-v1', worker='live-jvg-e2e-'+run+'/rabbit-commands-v1'),
                roles=roles, source=dict(path=SOURCE.relative_to(ROOT).as_posix(),
                    sha256=hashlib.sha256(SOURCE.read_text(encoding='utf-8').replace('\r\n', '\n').encode('utf-8')).hexdigest(),
                    normalization='UTF-8/LF', reviewed_commit='2d19cd7ea44adad3375679a222bec00e2b69b012'),
                source_compatibility='common-v1 follows DEV 3 published readiness proposal; standalone preserves generator resource names. Neither is approved.',
                standalone_database=source['database'],
                readiness_selects=dict(control=['betting_control.authorization_authority', 'betting_control.authorization_lease'],
                                       worker=['betting_worker.operation_authorization']),
                sql_scope='DEV 3 generator SQL creates only its standalone database. Do not rename/substitute it for live_jvg; owner-reviewed provisioning is required.',
                additions_requiring_owner_review=['archive owner/migrator/runtime roles and schema', 'match-state/dashboard/notification runtime roles',
                                                 'common-v1 provisioning distinct from standalone SQL', 'isolated host port proposal in override'],
                safe_flags=dict(consumers=False, storage_notification=False, publication=False, acceptance=False, authority='disabled', var_v2=False),
                approvals_required=['DBA/owners: new project, DBs, roles, migration order and grants',
                                    'DEV 2: Dashboard/Notification migrations, subscriptions and fixtures',
                                    'DEV 3: broker topology/vhost, runtime roles and BLOCKED-only consumer activation',
                                    'Team/operator: explicit permission for Docker startup, migrations, synthetic publication and restart',
                                    'Gabriel and Valeria: V2 semantics remain a separate approval; not needed for V1 rehearsal'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', required=True)
    parser.add_argument('--layout', choices=['common-v1', 'standalone'], default='common-v1')
    args = parser.parse_args()
    print(json.dumps(prepare(args.run, args.layout), indent=2))
