#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .mvp
docker info >/dev/null
if [[ ! -f .mvp/local.env ]]; then
  if docker volume inspect live-jvg-mvp_postgres_data >/dev/null 2>&1; then
    echo 'Existe el volumen MVP pero falta .mvp/local.env. Restaura ese archivo de credenciales; no se borrarán datos.' >&2
    exit 1
  fi
  (
  umask 077
  python3 - <<'PY'
import secrets,uuid
from pathlib import Path
Path('.mvp/local.env').write_text('MVP_DB_PASSWORD='+secrets.token_hex(20)+'\nMVP_RABBIT_PASSWORD='+secrets.token_hex(20)+'\nMVP_MATCH_ID=mvp-'+str(uuid.uuid4())+'\n')
PY
  )
fi
if [[ -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}/opt/homebrew/bin:$PATH"
command -v mvn >/dev/null || { echo 'Maven no disponible en PATH.' >&2; exit 1; }
for service in live-feed-producer match-state-service dashboard-backend; do
  mvn -B -ntp -f "services/$service/pom.xml" package > ".mvp/build-$service.log" 2>&1 || { tail -60 ".mvp/build-$service.log"; exit 1; }
done
docker compose --env-file .mvp/local.env up -d --build
python3 - <<'PY'
import time,urllib.request,subprocess,re
for port in [18080,18081]:
 for attempt in range(120):
  try:
   with urllib.request.urlopen(f'http://localhost:{port}/actuator/health',timeout=2) as r:
    if r.status==200:break
  except Exception:pass
  time.sleep(1)
 else:raise SystemExit(f'El servicio en {port} no quedó disponible; revisar docker compose logs.')
command=['docker','compose','--env-file','.mvp/local.env','exec','-T','kafka','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','kafka:9092','--describe','--group','match-state-service','--state']
for attempt in range(40):
 result=subprocess.run(command,capture_output=True,text=True,timeout=15)
 if result.returncode==0 and re.search(r'\bStable\b',result.stdout):break
 time.sleep(1)
else:raise SystemExit('El consumidor Kafka no quedó estable. Revisa los logs de match-state.')
print('Live JVG disponible: http://localhost:18080')
PY
