# Guía preparada de despliegue y demostración — NO EJECUTADA

No ejecutar los pasos de infraestructura sin autorización nueva. Los comandos aquí descritos no constituyen
permiso de ejecución. No usar recursos anteriores, no purgar colas, no borrar volúmenes ni reinicializar offsets.

## 1. Dependencias y validaciones locales — completadas

Desde `/private/tmp/live-jvg-local-academic-v1`, Java17:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
# Resolución original ya autorizada y ejecutada, sin downgrade:
mvn -U -Dmaven.repo.local=/private/tmp/live-jvg-archiver-m2 -f services/notification-backend/pom.xml verify
# Tras tener las dependencias, repetir también offline:
mvn -o -Dmaven.repo.local=/private/tmp/live-jvg-archiver-m2 -f services/notification-backend/pom.xml verify
```

La suite genera `services/notification-backend/target/live-alert-rest-fixture.json`. Solo si pertenece a ESTA
revisión y pasó la suite, usar el script existente con el node_modules verificado del frontend:

```sh
JVG_FRONTEND_NODE_MODULES=/Users/valeriaestefaniagongoratorres/Live-JVG/services/dashboard-frontend/node_modules \
  node services/notification-backend/scripts/verify-react-alert-contract.cjs
```

El puente pasó con el fixture actual, no con uno antiguo ni fabricado. Las dependencias Python están en
`/private/tmp/live-jvg-validation-venv`, con paquetes globales excluidos. No se alteraron dependencias de aplicación.
Validaciones repetibles sin infraestructura:

```sh
/private/tmp/live-jvg-validation-venv/bin/python scripts/validate-dev1-contracts.py
/private/tmp/live-jvg-validation-venv/bin/python scripts/test-validate-dev1-contracts.py
/private/tmp/live-jvg-validation-venv/bin/python scripts/validate-integrated-contracts.py
/private/tmp/live-jvg-validation-venv/bin/python scripts/test-integrated-contracts.py
```

La última pareja requiere los fixtures exportados por la suite Notification, ya presentes en este worktree.
Ver `skipped-tests.md`: las pruebas PostgreSQL/Kafka/Rabbit permanecen sin autorización ni ejecución.

## 2. Preparación de texto offline (sin Docker/SQL)

Usar un RUN nuevo y conservarlo durante todo el ensayo; nunca reutilizar 5c84548cf006 por comodidad.
Los planes ya generados durante esta tarea, sin ejecutar recursos, están en
`/private/tmp/live-jvg-integration-evidence/prepared-d97d2cbc7f35`.
Para una propuesta nueva:

```sh
umask 077
RUN="$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')"
REVIEW="$(mktemp -d /private/tmp/live-jvg-academic-review.XXXXXX)"
python3 scripts/e2e/prepare-review.py --run "$RUN" > "$REVIEW/manifest.json"
python3 services/notification-backend/docs/common-v1-proposal/prepare_common_v1.py \
  --run "$RUN" --output-dir "$REVIEW/db" --render
python3 scripts/academic-e2e.py plan --run "$RUN" --manifest "$REVIEW/manifest.json" \
  --output "$REVIEW/academic-plan.json"
```

El manifiesto de recursos y el plan SQL común son documentos distintos: no sustituir uno por otro.
Guardar contraseñas y atestaciones fuera de Git; no usar las credenciales dummy del validador como credenciales reales.

## 3. Provisión futura: autorización separada

Primero el operador debe demostrar con inventarios reales que proyecto `live-jvg-e2e-$RUN`, red, volúmenes,
puertos y contenedores son NUEVOS. El prefijo de un nombre no prueba que un recurso no exista ya.
No se comprobó disponibilidad de puertos ni estado Docker en esta tarea.

Orden futuro de revisión/provisión:

1. PostgreSQL17 nuevo, base común live_jvg, seis schemas con owners NOLOGIN y migradores/runtime separados.
2. Revisar `10-provision`, la atestación de system_identifier y las migraciones inventariadas. Match State V1–V4;
   Dashboard V1–V3; Notification V1–V2; Archiver V1–V2; Control V1–V3; cross-owner 20 DESPUÉS de Control V2
   y ANTES de Worker; Worker V1–V3. No reescribir migraciones ya aplicadas ni inferir backfill de source sequence.
3. `30-passive-grants`, `40-fixtures` y `50-select-preflight`: los runtime solo SELECT en arranque pasivo.
   Los archivos contienen guardas de proyecto, cluster y base vacía. Revisarlos no autoriza ejecutarlos.
4. Kafka nuevo, topic match_events, grupos/source IDs exclusivos del manifiesto. No ZooKeeper para la imagen
   KRaft declarada. No compartir consumer group entre Archiver, Match State y Suspension.
5. Rabbit nuevo: vhost deportivo exclusivo del ensayo y vhost académico `dev3_it_${RUN}_academic` separado.
   Provisión de recursos con identidad de setup; no entregar credenciales de setup a runtime.
6. Topic `live_updates` → cola Dashboard con `score.e2e-$RUN`; Fanout `live_alerts` →
   `notification.dev3_it_${RUN}_alerts` con binding vacío. No mezclar la antigua cola Topic Notification con Fanout.
   Sus DLX/DLQ se provisionan según las configuraciones existentes.
7. Vhost académico: únicamente `betting_commands`, `betting_commands.dlx` (direct), `betting_commands.dlq`,
   binding rejected y DLX/rejected en la cola principal. No aceptar declaraciones de topología en runtime Worker.

Los propietarios deben concretar los comandos Docker/Flyway/Rabbit con las credenciales y destinos aprobados.
No dar un comando genérico `compose up` como autorización global. El Compose base heredado NO es un contrato
pasivo global: solo es válida la combinación de archivos y modelo efectivo revisados.

## 4. PASSIVE/OFF primero

Revisar base + `scripts/e2e/compose.manifest-proposed.yml`, con entorno privado completo. Compose >=2.24.4.
El preflight debe ver los cuatro ACADEMIC_* false, generación/publicación LiveAlert false, consumidores false,
Flyway/SQL init/RabbitAdmin automático false, catálogo/recovery/publicación OFF y autoridad disabled.
Dependencias de arranque se neutralizan; kafka-init permanece provisioning-explicit en el overlay.

Tras autorización, resolver Compose a JSON y validar SOLO la configuración:

```sh
python3 scripts/e2e-harness.py check-config --run "$RUN" --manifest "$REVIEW/manifest.json" \
  --phase passive --config-json "$REVIEW/off-resolved.json"
```

El archivo resuelto puede contener secretos: permanecerá privado fuera de Git. El comando de validación no
inicia servicios. El arranque pasivo posterior exige otra aprobación y demostrar ausencia de escrituras,
publicaciones, declaraciones y consumo; health UP no concede autoridad de apuestas.

## 5. Ensayo académico ON futuro (autorización distinta)

Revisar `scripts/e2e/compose.academic-proposed.yml` ADEMÁS de los dos anteriores. Es una propuesta ON;
no usarla en el ensayo pasivo ni aplicarla en esta tarea.

Valores adicionales requeridos en el entorno privado:

- E2E_ACADEMIC_VHOST=`dev3_it_${RUN}_academic`.
- E2E_NOTIFICATION_ALERT_QUEUE=`notification.dev3_it_${RUN}_alerts`.
- E2E_ACADEMIC_PUBLISHER_USERNAME=`dev3_it_${RUN}_academic_pub`.
- E2E_ACADEMIC_WORKER_USERNAME=`dev3_it_${RUN}_academic_worker`.
- E2E_SCORE_PUBLISHER_USERNAME=`dev3_it_${RUN}_score_pub`.
- E2E_SCORE_READER_USERNAME=`dev3_it_${RUN}_score_reader`.
- E2E_ALERT_READER_USERNAME=`dev3_it_${RUN}_alert_reader`.
- Sus cinco E2E_*_PASSWORD deben venir del secreto privado aprobado, no del repositorio.

Archiver no usa Rabbit y el overlay académico elimina sus credenciales administrativas heredadas.
Revisar `rabbit-academic-proposed.json`. Publicar por el exchange por defecto NO proporciona aislamiento
por routing key. La barrera principal es el vhost académico exclusivo, sin colas sensibles, más permisos
runtime mínimos. Verificar con el broker real la representación de permisos del exchange por defecto y
probar accesos denegados al vhost deportivo antes de activar la publicación académica.

Aplicar solo tras aprobación los permisos `70-academic-dml.proposed.sql`, no los legacy de 60. El Worker
solo escribe academic_command/academic_delivery y su contador de entregas. Nunca operation ni authorization.
Los otros servicios reciben sus DML deportivos revisados. No se emite ningún lease.

El modelo ON futuro mantiene acceptance=false, authorization=disabled y Worker de operaciones OFF. Habilita
solo los adapters académicos, deportes, alertas y Dashboard estricto aprobados. Requiere esquema nuevo/inbox
vacía y Match State durable desde secuencia 1, no un origen memory ni historia parcialmente restaurada.

Comprobación offline del modelo ON resuelto, después de la autorización de revisión de ese modelo:

```sh
export LIVE_JVG_E2E_APPROVED_PROJECT="live-jvg-e2e-$RUN"
export LIVE_JVG_ACADEMIC_APPROVED_PROJECT="live-jvg-e2e-$RUN"
python3 scripts/academic-e2e.py check-config --run "$RUN" --manifest "$REVIEW/manifest.json" \
  --config-json "$REVIEW/academic-resolved.json" --output "$REVIEW/unused.json"
```

SOLO con infraestructura provisionada y activación expresamente autorizada, se preparó este comando:

```sh
export LIVE_JVG_ACADEMIC_EXECUTION_APPROVAL=I_APPROVE_ISOLATED_ACADEMIC_EXECUTION
python3 scripts/academic-e2e.py execute-approved --run "$RUN" --manifest "$REVIEW/manifest.json" \
  --plan-json "$REVIEW/academic-plan.json" \
  --compose-file docker-compose.yml \
  --compose-file scripts/e2e/compose.manifest-proposed.yml \
  --compose-file scripts/e2e/compose.academic-proposed.yml \
  --output "$REVIEW/academic-result.json"
```

No se ejecutó. El harness no provisiona nada. Rechaza resultados existentes, valida ambas réplicas reales antes
publicar y usa POST `/api/matches/{matchId}/events` del productor real. Ningún plan offline es un resultado E2E.

| Barrera | Marcador/VAR | Fanout acumulado | Comando / resultado |
|---|---|---:|---|
| GOAL | 1-0 CLEAR | 1 | SUSPEND_BETS / SIMULATED_SUSPENDED |
| VAR_CHECK | 1-0 IN_PROGRESS | 2 | SUSPEND_BETS / SIMULATED_SUSPENDED |
| GOAL_ANNULLED referencia GOAL | 0-0 CLEAR | 3 | RESUME_BETS / SIMULATED_RESUMED |
| MATCH_CONTINUES | 0-0 CLEAR | 3 | RESUME_BETS / SIMULATED_RESUMED |
| Duplicado GOAL + nueva barrera | 0-0 CLEAR | 3 | solo un comando adicional por la barrera |

En todas las barreras: counts coherentes Match State/Dashboard/Archiver/Control, comandos y outbox publicados,
source_sequence contigua; cero operaciones, ACCEPTED, grants, leases y cuarentena académica. Mercado real SUSPENDED.
La reanudación es una acción académica, no OPEN ni permiso para apostar. El resultado conserva `certified=false`:
el éxito de esas consultas no certifica frontend, distribución entre Workers ni resiliencia completa.

## 6. React y conexiones reales — futuro autorizado

El overlay académico propone Notification REST `127.0.0.1:18192`; Dashboard usa `127.0.0.1:18190`.
El origen React propuesto es `http://127.0.0.1:15173`, permitido explícitamente por ambos backends.
Antes de usar esos puertos se debe comprobar que están libres; no se comprobaron en esta tarea.
No reutilizar una cola Notification Topic para las alertas Fanout.

Con dependencias frontend disponibles y autorización para servir la UI, preparar el build con endpoints exactos:

```sh
VITE_DASHBOARD_ORIGIN=http://127.0.0.1:18190 \
VITE_WS_URL=ws://127.0.0.1:18190/ws \
VITE_NOTIFICATION_ORIGIN=http://127.0.0.1:18192 \
  npm --prefix services/dashboard-frontend run build
# Arranque de servicio HTTP: requiere la autorización operativa, no se ejecutó:
python3 -m http.server 15173 --bind 127.0.0.1 --directory services/dashboard-frontend/dist
```

En el navegador abrir `http://127.0.0.1:15173` (sin mode=demo), elegir el partido del RUN desde catálogo,
registrar REST y STOMP y observar 1-0 y 0-0 con alertas reales. Comparar event_id/revision, no screenshots
sin origen trazable. Probar reconexión solo tras autorizarla. Un build exitoso no prueba ese recorrido.

## 7. Evidencias adicionales y fallos inducidos (autorización independiente)

| Caso | Evidencia obligatoria y PASS | FAIL / límite |
|---|---|---|
| Dos Workers | dos IDs/ordinales 1/2, misma imagen, consumers activos; dos academic_consumer_instance_id en auditoría durante lote controlado | dos procesos no demuestran distribución; un solo ejecutor observado no certifica el caso |
| Reentrega Rabbit | mismo command_id/canonical; un resultado; delivery_attempts aumenta; recibo por entrega | resultado distinto, doble efecto o pérdida de custodia |
| Conflicto | mismo ID/contenido distinto en DLQ con original preservado; resultado previo intacto | reemplazo silencioso o ACK sin custodia |
| Broker indisponible | marker permanece pendiente; tras recuperación entrega con mismo ID, sin doble resultado | publicado sin confirm, falta de return control o pérdida |
| DLQ/replay | original + identidad + motivo; republicar SOLO tras subsanar causa y en orden, sin purgar | replay ciego de un hueco de secuencia o aceptación de antiguo |
| Restart | reiniciar SOLO contenedores NUEVOS autorizados, conservando volúmenes; snapshot/ledger/contador persisten | reset de historia, offsets o base |
| ORDER02 estricto | antiguo desconocido no modifica snapshot; conflicto/hueco en custodia; replay contiguo tras revisión | retroceso del marcador o bypass sin cabecera |
| REST/STOMP/React | respuestas reales y frames con stream_id/revision; goal/annul visibles; reconexión sin retroceso | solo comparar SQL o fixtures no certifica UI |
| Auditoría | bytes/event_id/clave/match/reference en Kafka, ledger, ambas outboxes, inbox, snapshots y resultados | asociaciones por timestamp o imágenes fabricadas |
| Recursos | límites efectivos inspect + medición durante ensayo | YAML no prueba enforcement ni capacidad |

La consulta de distribución preparada es SELECT sobre academic_delivery.original_headers::jsonb,
clave academic_consumer_instance_id (identidad diagnóstica, no autoridad). Guardar comandos, tiempos,
logs y resultados por caso, con secretos redactados. No eliminar recursos ni evidencias automáticamente.

Cierre del proyecto: requiere aprobar contratos/metadato, ejecutar y aprobar todas
las barreras y casos reales requeridos, revisar riesgos residuales ORDER02 y autorizar la entrega Git.

## 8. Ocho omitidas restantes: selección real segura y exclusiones adicionales

**PREPARADO, NO EJECUTADO.** Los 14 AtomicPostgresConcurrencyTest quedan fuera del ensayo estándar.
Además, la legacy de Control indicada en P02 emite un lease: tampoco puede ejecutarse con la autorización académica.
Los tests se seleccionan por módulo, clase y método; no usar comodines *Postgres*, activar todos los flags
ni el perfil de atomicidad. Cada caso escribe su propia evidencia y se ejecuta en recursos nuevos aprobados.

| Caso | Servicio / selector exacto | Infraestructura / datos | Riesgo y decisión |
|---|---|---|---|
| P01 | historical-archiver / PreparedPostgresIntegrationTest#v1UpgradePreservesEvidenceAndV2SupportsMatchScopedIdentityAndRecovery | PostgreSQL17, base nueva dev3_it_archiver_${RUN}_p01 | MEDIO: V1→V2, inserts y dedup; candidata tras autorización |
| P02 | betting-suspension-service / PreparedPostgresIntegrationTest#stateReviewClosureDedupAndOutboxSurviveNewAdapter | PostgreSQL17, cierres OPEN y lease sintético | ALTO: EXCLUIDA; no ejecutar en el ensayo académico |
| P03 | betting-suspension-service / AcademicPreparedPostgresTest#academicPersistenceRestartDedupAndRollback | PostgreSQL17, dev3_it_betting_control_${RUN}_p03 | MEDIO: V1–V3, rollback/ALTER de prueba; candidata aislada |
| P04 | betting-worker / AcademicPreparedPostgresTest#academicPersistenceRestartDedupAndRollback | PostgreSQL17, dev3_it_betting_worker_${RUN}_p04 | MEDIO: V1–V3, rollback/ALTER; candidata aislada |
| P05 | betting-worker / PreparedPostgresIntegrationTest#blockedOperationsAndSuspensionFenceSurviveNewAdapterAndDuplicate | PostgreSQL17, dev3_it_betting_worker_${RUN}_p05 | MEDIO: crea operaciones BLOCKED y evidencia OPEN obsoleta simulada, no grants; candidata en BD SEPARADA del E2E |
| K01 | historical-archiver / PreparedKafkaBrokerIntegrationTest#approvedIsolatedTopicExistsWithoutCreatingItOrCommittingOffsets | Kafka nuevo, topic dev3_it_${RUN}_probe_events | BAJO: metadata read-only; candidata tras autorización |
| K02 | betting-suspension-service / mismo selector Kafka de K01 | mismo Kafka/topic de probes | BAJO: metadata read-only; candidata tras autorización |
| R01 | betting-worker / PreparedRabbitBrokerIntegrationTest#approvedIsolatedCommandDestinationExists | Rabbit nuevo, vhost de probe y exchange/queue dev3_it_* | BAJO: declaración pasiva; candidata tras autorización |

P01/P03/P04/P05 usan un rol **fixture/migrador** limitado a bases desechables, no las credenciales runtime del MVP.
No certifican los permisos mínimos del runtime. Cada caso necesita base distinta: sus guards rechazan un schema
preexistente y algunos casos añaden constraints para inducir rollback. No reutilizar esas bases para E2E.

P04/P05 deben respetar la dependencia Control V2 → Worker V2 del modelo común: preparar Control V1–V3
con el migrador aprobado antes del test Worker, dejando el schema Worker ausente para que lo migre el test.
No usar P02 ni AtomicPostgresConcurrencyTest como bootstrap. Una alternativa de Flyway para esa preparación,
solo tras aprobar DDL y con configuración PRIVADA revisada (URL de la base Worker de ese caso, rol fixture,
schema/defaultSchema betting_control, locations filesystem de Control, cleanDisabled=true, baselineOnMigrate=false), es:

```sh
# Futuro, NO EJECUTAR ahora. 11.7.2 es la versión Flyway gestionada por el parent 3.5.7 de Control.
mvn -B -ntp -f services/betting-suspension-service/pom.xml \
  "-Dflyway.configFiles=$PRIVATE_CONTROL_FLYWAY_CONFIG" \
  org.flywaydb:flyway-maven-plugin:11.7.2:migrate
```

No pasar contraseñas con -Dflyway.password en CLI ni versionar ese archivo. Esto es preparación de laboratorio,
no demuestra que la autoridad esté habilitada y no autoriza emitir leases. Si falla una dependencia/permiso,
conservar el error; no borrar schemas, cambiar versiones ni debilitar guards para continuar.

### Comandos de los cuatro casos PostgreSQL permitidos bajo autorización futura

Prerequisitos: nuevo servidor PostgreSQL17 autorizado; puerto libre y distinto de los excluidos por IsolatedPostgres;
las cuatro bases anteriores VACÍAS; rol LOGIN dev3_it_* sin SUPERUSER/CREATEDB/CREATEROLE, propietario solo
de esas bases de laboratorio; contraseña privada. Para P04/P05 aplicar primero el bootstrap Control ya descrito.
No se comprobó conectividad ni se crearon bases/roles aquí.

```sh
# DEFINICIÓN PREPARADA: no llamar esta función hasta recibir aprobación específica.
pg_case() {
  test "${DEV3_LAB_EXECUTION_APPROVAL:-}" = I_APPROVE_NEW_ISOLATED_PG_TESTS || return 64
  : "${RUN:?Fresh run required}" "${DEV3_LAB_PG_PORT:?Approved free port required}"
  : "${DEV3_LAB_FIXTURE_ROLE:?Dedicated fixture role required}" "${DEV3_LAB_FIXTURE_PASSWORD:?Private password required}"
  local module="$1" selector="$2" database="$3" mode=DEV3_RUN_POSTGRES_TESTS
  case "$module/$selector" in
    historical-archiver/PreparedPostgresIntegrationTest#v1UpgradePreservesEvidenceAndV2SupportsMatchScopedIdentityAndRecovery) ;;
    betting-suspension-service/AcademicPreparedPostgresTest#academicPersistenceRestartDedupAndRollback) mode=DEV3_RUN_ACADEMIC_POSTGRES_TESTS ;;
    betting-worker/AcademicPreparedPostgresTest#academicPersistenceRestartDedupAndRollback) mode=DEV3_RUN_ACADEMIC_POSTGRES_TESTS ;;
    betting-worker/PreparedPostgresIntegrationTest#blockedOperationsAndSuspensionFenceSurviveNewAdapterAndDuplicate) ;;
    *) echo 'Selector outside the no-grants laboratory allowlist' >&2; return 64 ;;
  esac
  env -u DEV3_RUN_ATOMIC_POSTGRES_TESTS -u DEV3_RUN_POSTGRES_TESTS \
    -u DEV3_RUN_ACADEMIC_POSTGRES_TESTS -u DEV3_RUN_ISOLATED_BROKER_TESTS \
    "$mode=true" DEV3_IT_MIGRATION_APPROVAL=I_APPROVE_ISOLATED_MIGRATIONS \
    "DEV3_IT_DB_URL=jdbc:postgresql://127.0.0.1:${DEV3_LAB_PG_PORT}/${database}" \
    "DEV3_IT_DB_USERNAME=$DEV3_LAB_FIXTURE_ROLE" "DEV3_IT_DB_PASSWORD=$DEV3_LAB_FIXTURE_PASSWORD" \
    mvn -B -ntp -o -Dmaven.repo.local=/private/tmp/live-jvg-archiver-m2 \
      -f "services/$module/pom.xml" "-Dtest=$selector" \
      -DfailIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=true test
}

# SOLO tras la autorización futura y los prerrequisitos de cada caso:
pg_case historical-archiver \
 'PreparedPostgresIntegrationTest#v1UpgradePreservesEvidenceAndV2SupportsMatchScopedIdentityAndRecovery' \
 "dev3_it_archiver_${RUN}_p01"
pg_case betting-suspension-service \
 'AcademicPreparedPostgresTest#academicPersistenceRestartDedupAndRollback' \
 "dev3_it_betting_control_${RUN}_p03"
pg_case betting-worker \
 'AcademicPreparedPostgresTest#academicPersistenceRestartDedupAndRollback' \
 "dev3_it_betting_worker_${RUN}_p04"
pg_case betting-worker \
 'PreparedPostgresIntegrationTest#blockedOperationsAndSuspensionFenceSurviveNewAdapterAndDuplicate' \
 "dev3_it_betting_worker_${RUN}_p05"
```

Las llamadas se preparan para ejecución INDIVIDUAL: guardar log y el XML de la clase seleccionada después de
cada una, antes de pasar al caso siguiente. No sumar XML antiguos que permanezcan en target/surefire-reports.
PASS por caso: tests=1, failures=0, errors=0, skipped=0 y assertions completas. Una omisión no es PASS.
P05 debe conservar cero ACCEPTED/grants; sus operaciones BLOCKED impiden reutilizar esa BD en el ensayo
académico, que exige cero operaciones en total. P03/P04 no deben emitir leases ni grants.

### Comandos de probes Kafka y Rabbit preparados

Requieren infraestructura y topologías de probes ya aprovisionadas y autorizadas. No crean topics/queues ni
certifican publicación/consumo. No reemplazan las pruebas match_events/live_alerts/betting_commands del E2E.
Usar endpoints loopback aislados, puertos alternativos, rol de probe y vhost distinto de cualquier recurso previo.

```sh
# SOLO con autorización futura; las variables de conexión deben venir del inventario privado aprobado.
for module in historical-archiver betting-suspension-service; do
  env -u DEV3_RUN_ATOMIC_POSTGRES_TESTS -u DEV3_RUN_POSTGRES_TESTS -u DEV3_RUN_ACADEMIC_POSTGRES_TESTS \
    DEV3_RUN_ISOLATED_BROKER_TESTS=true DEV3_BROKER_APPROVAL=I_APPROVE_ISOLATED_BROKER_PROBES \
    "DEV3_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:${DEV3_LAB_KAFKA_PORT:?Approved port}" \
    "DEV3_MATCH_EVENTS_TOPIC=dev3_it_${RUN:?Fresh run}_probe_events" \
    mvn -B -ntp -o -Dmaven.repo.local=/private/tmp/live-jvg-archiver-m2 -f "services/$module/pom.xml" \
      '-Dtest=PreparedKafkaBrokerIntegrationTest#approvedIsolatedTopicExistsWithoutCreatingItOrCommittingOffsets' \
      -DfailIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=true test
  # Conservar XML/log por módulo antes de continuar.
done

env -u DEV3_RUN_ATOMIC_POSTGRES_TESTS -u DEV3_RUN_POSTGRES_TESTS -u DEV3_RUN_ACADEMIC_POSTGRES_TESTS \
  DEV3_RUN_ISOLATED_BROKER_TESTS=true DEV3_BROKER_APPROVAL=I_APPROVE_ISOLATED_BROKER_PROBES \
  DEV3_RABBITMQ_HOST=127.0.0.1 "DEV3_RABBITMQ_PORT=${DEV3_LAB_RABBIT_PORT:?Approved port}" \
  "DEV3_RABBITMQ_USERNAME=${DEV3_LAB_RABBIT_USER:?Dedicated probe role}" \
  "DEV3_RABBITMQ_PASSWORD=${DEV3_LAB_RABBIT_PASSWORD:?Private password}" \
  "DEV3_RABBITMQ_VHOST=dev3_it_${RUN:?Fresh run}_probe" \
  "DEV3_WORKER_EXCHANGE=dev3_it_${RUN}_probe_exchange" "DEV3_WORKER_QUEUE=dev3_it_${RUN}_probe_queue" \
  mvn -B -ntp -o -Dmaven.repo.local=/private/tmp/live-jvg-archiver-m2 -f services/betting-worker/pom.xml \
    '-Dtest=PreparedRabbitBrokerIntegrationTest#approvedIsolatedCommandDestinationExists' \
    -DfailIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=true test
```

Estos comandos no habilitan los consumidores de aplicación ni una autoridad, pero sí hacen conexiones reales
si se ejecutan: NO están autorizados ahora. PASS de cada probe: un test ejecutado sin error/omisión, recursos
esperados y ninguna creación/publicación/commit de offsets. Para Rabbit, la semántica de permisos de declaración
pasiva debe verificarse con el broker; no conceder configure global para forzar el resultado.

## 9. Orden de autorización, criterios y limpieza futura

1. Revisar contratos/metadato, topologías y perfil de riesgo. Resolver específicamente la aprobación del nuevo
   header de secuencia y el nombre reference_event_id frente al término annuls_event_id del enunciado.
2. Aprobar recursos NUEVOS e inventario: contenedores/labels, puertos libres, redes y tres volúmenes nombrados.
   Elegir RUN/proyecto nuevos; conservar evidencia de que no existen previamente. El laboratorio de los casos P/K/R
   y el stack E2E son proyectos distintos; no compartir DB de fixtures con la demostración.
3. Aprobar provisión, roles y migraciones. Son 17 migraciones: Match State4, Dashboard3, Notification2,
   Archiver2, Control3, Worker3. Control y cross-owner preceden a Worker en common-v1. Ownership separado.
4. Aplicar solo SELECT pasivos de 30 antes de PASSIVE/OFF. Verificar cero escrituras/consumo/publicación
   y ausencia de autodeclaraciones. Esto requiere observación real; los tests YAML no bastan.
5. Aprobar por separado vhosts/ACL/DML70 y ON académico. No aplicar el legacy60. Mantener aceptación=false,
   authority=disabled, consumer legacy Worker=false, topology académica=false y cero grants/leases/ACCEPTED.
6. Ejecutar el harness preparado de las secciones 5/6 y luego los casos autorizados de la sección 7.
   Los 14 atómicos y P02 no entran en ninguna de estas aprobaciones.

Evidencia mínima por caso: SHA de integración, RUN, plan revisado/hash, comando y resultado, XML/log, IDs/images
actuales de contenedores, topic/key/event_id/offsets, confirms/returns observados, outboxes, inbox/seq/snapshots,
LiveAlert, AcademicCommand y sus resultados/delivery_attempts/instance_id. Redactar secretos antes de compartir.
FAIL inmediato: cualquier ACCEPTED/grant/lease, salto o retroceso de estado aplicado, recurso previo/reutilizado,
permisos excesivos o resultado inventado/omitido. Una recuperación parcial se informa como parcial, no PASS global.

### Limpieza conservadora (NO EJECUTADA)

Primero capturar evidencias y detener nuevas publicaciones; conservar los tres volúmenes y las bases. Por defecto
solo STOP. No usar down -v, prune, DROP DATABASE/SCHEMA, purge, reset de offsets, --remove-orphans ni killall.
Usar los mismos archivos y entorno privado revisados que crearon ESTE proyecto, nunca el Compose global solo.

```sh
# Comandos preparados para DESPUÉS del ensayo y su autorización de parada/limpieza.
# PROJECT debe proceder del plan revisado: live-jvg-e2e-$RUN. PRIVATE_ENV fuera de Git.
COMPOSE=(docker compose -p "$PROJECT" --env-file "$PRIVATE_ENV" \
  -f docker-compose.yml -f scripts/e2e/compose.manifest-proposed.yml \
  -f scripts/e2e/compose.academic-proposed.yml --profile integration-prepared)
"${COMPOSE[@]}" ps --all --format json > "$REVIEW/owned-containers-before-stop.json"
# Verificar IDs/labels contra el inventario de creación; si hay un recurso ajeno, PARAR la limpieza.
"${COMPOSE[@]}" stop -t 30 producer
"${COMPOSE[@]}" logs --no-color > "$REVIEW/stack-logs-private.txt"
# Revisar/redactar logs antes de compartir; NO guardar docker inspect completo con Env ni el envfile en Git.
"${COMPOSE[@]}" stop -t 30 match-state dashboard notification archiver suspension worker
"${COMPOSE[@]}" stop -t 30 kafka rabbitmq postgres
"${COMPOSE[@]}" ps --all --format json > "$REVIEW/owned-containers-after-stop.json"

# OPCIONAL: solo después de otra aprobación de retirada de contenedores/red, identidad comprobada
# y evidencias resguardadas. Sin -v: conservar postgres_data, kafka_data y rabbit_data.
# "${COMPOSE[@]}" down
```

Para React en foreground usar Ctrl-C del proceso concreto. Si fue arrancado en background, registrar el PID
al crearlo y verificar que sigue siendo ESE servidor antes de pararlo; no matar procesos por nombre.
El laboratorio se detiene con su propio proyecto y config, nunca con el PROJECT E2E. No eliminar bases,
roles ni volúmenes automáticamente; la retirada definitiva exige un inventario y autorización posteriores.

## 10. Dictamen previo a recursos reales

No se ha reproducido un bloqueo adicional de código para iniciar un ensayo aislado correctamente acotado:
Notification y contratos pasan localmente. Eso NO garantiza arranque, permisos, migraciones ni E2E físico.
Los límites pendientes son aprobación de contratos/metadato, aislamiento y credenciales efectivos,
provisión/migraciones/ACL, habilitación académica separada y comportamiento real de brokers/BD/UI.
ORDER02 legado continúa abierto; el modo estricto requiere el protocolo y replay inicial documentados.
El estado del commit V2 se verifica con Git y no se presume a partir de archivos modificados.

### ACL Rabbit propuestas con comandos, todavía SIN APLICAR

Solo después de crear broker, vhost y usuarios NUEVOS aprobados, con claves fuera de Git y sin tags admin para
runtime. Los nombres de usuario deben coincidir con los exigidos por AcademicHarness. No crear/reutilizar usuarios
con nombre genérico en un broker previo. Estos comandos no crean colas, no sustituyen su provisión y no prueban
que la autorización del exchange por defecto esté bien aplicada hasta observar el broker real.

```sh
# FUTURO: COMPOSE es el array del proyecto nuevo revisado, definido en la sección de limpieza.
# Ese array debe apuntar exactamente al stack creado, nunca a otro proyecto ni al laboratorio anterior.
ACADEMIC_VHOST="dev3_it_${RUN}_academic"
"${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl set_permissions -p "$ACADEMIC_VHOST" \
  "dev3_it_${RUN}_academic_pub" '^$' '^(amq\.default|betting_commands)$' '^$'
"${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl set_permissions -p "$ACADEMIC_VHOST" \
  "dev3_it_${RUN}_academic_worker" '^$' '^betting_commands\.dlx$' '^betting_commands$'
"${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl list_permissions -p "$ACADEMIC_VHOST"
"${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl list_user_permissions "dev3_it_${RUN}_academic_pub"
"${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl list_user_permissions "dev3_it_${RUN}_academic_worker"
```

El regex del publicador es una propuesta explícita que contempla el nombre de permiso del exchange por defecto
y la cola destino. NO afirma aislamiento por routing key; aunque se reduzca ese regex, escribir al default exchange
puede alcanzar otras colas del mismo vhost. Por eso el vhost académico solo puede contener las dos colas académicas
y su DLX, sin datos sensibles ni accesos concedidos a otros vhosts. Si el broker requiere otro permiso o lo rechaza,
conservar el error y revisar con permisos exactos: no otorgar `.*` para forzar PASS.

Permisos deportivos a revisar/aplicar por el provisionador, separados de los anteriores:

| Identidad | configure | write | read |
|---|---|---|---|
| dev3_it_RUN_score_pub | ninguno con topología preaprovisionada | live_updates y live_alerts | ninguno |
| dev3_it_RUN_score_reader | ninguno con RabbitAdmin automático OFF | DLX Dashboard | cola Dashboard |
| dev3_it_RUN_alert_reader | live_alerts + su cola/DLX/DLQ, porque Notification tiene RabbitAdmin explícito en ON | su cola y DLQ para bindings + su DLX para custodia | live_alerts y su DLX para bindings + su cola para consumo |

Notification requiere revisar las declaraciones y sus bindings antes de aplicar el regex final; no confundir
SPRING_RABBITMQ_DYNAMIC=false con deshabilitar un RabbitAdmin explícito cuando messaging=true. En OFF,
notification.messaging=false impide crear ese componente. No se otorgan permisos ni se activa mensajería aquí.

## 11. Guardia de errores para los comandos preparados

Ejecutar cada caso de forma individual con captura de exit code, sin set -x, y detener el procedimiento ante
cualquier error. Un PASS requiere el XML NUEVO del selector con tests=1/skipped=0, no la suma de informes
anteriores. Para una sesión de ejecución autorizada no interactiva puede usarse `set -e`; no encadenar casos
con `;` ni ocultar fallos con `|| true`. Nunca ejecutar todo el bloque como si la provisión ya estuviera aprobada.

Una aprobación del ensayo académico NO autoriza los 14 AtomicPostgresConcurrencyTest ni P02. Una aprobación
de dependencias tampoco autoriza ninguno de los comandos de conexión, provisión, migración, arranque o limpieza.
