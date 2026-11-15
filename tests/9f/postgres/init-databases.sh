#!/usr/bin/env bash
set -euo pipefail
[[ "${AUDIT_RUN_ID:?Required isolated run}" =~ ^[a-f0-9]{12}$ ]] || exit 1
for suite in state dashboard brokers quarantine; do
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
       --set db="live_jvg_test_${AUDIT_RUN_ID}_${suite}" <<'SQL'
SELECT format('CREATE DATABASE %I', :'db') \gexec
SQL
done
