# Guía preparada de despliegue y demostración — NO EJECUTADA

No ejecutar los pasos de infraestructura sin autorización nueva. Los comandos aquí descritos no constituyen
permiso de ejecución. No usar recursos anteriores, no purgar colas, no borrar volúmenes ni reinicializar offsets.

## 1. Cerrar los bloqueos de herramientas

Desde `/private/tmp/live-jvg-local-academic-v1`, Java17:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
# Requiere autorización de acceso a repositorios para resolver el POM ORIGINAL, no un downgrade:
mvn -U -f services/notification-backend/pom.xml verify
# Tras tener las dependencias, repetir también offline:
mvn -o -f services/notification-backend/pom.xml verify
```

La suite genera `services/notification-backend/target/live-alert-rest-fixture.json`. Solo si pertenece a ESTA
revisión y pasó la suite, usar el script existente con el node_modules verificado del frontend:

```sh
JVG_FRONTEND_NODE_MODULES=/Users/valeriaestefaniagongoratorres/Live-JVG/services/dashboard-frontend/node_modules \
  node services/notification-backend/scripts/verify-react-alert-contract.cjs
```

No se ejecutó ese puente con un fixture antiguo ni fabricado. También falta disponer de fastjsonschema y
ruamel.yaml para `python3 scripts/validate-dev1-contracts.py`; no se instalaron herramientas en esta tarea.

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

Cierre del proyecto: requiere resolver Notification, aprobar contratos/metadato, ejecutar y aprobar todas
las barreras y casos reales requeridos, revisar riesgos residuales ORDER02 y autorizar la entrega Git.
