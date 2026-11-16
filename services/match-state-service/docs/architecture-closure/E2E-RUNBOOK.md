# Procedimiento E2E: preparación y ejecución aislada

Estado: preparado, NO ejecutado en infraestructura real. No certificación E2E.
El harness ejecutable cubre V1. V2, anulación con doble identidad y causalidad de
suspensión/autoridad siguen gates en CONTRACT-DECISIONS.md y TEAM-CONFIG.md.

1. Elegir run nuevo de doce hex minúsculas. Ejemplo offline:
   `python scripts/e2e-harness.py plan --run abcdef123456`.
   Produce tmp/live-jvg-e2e-abcdef123456/plan.json sin Docker ni red.
2. Obtener decisiones/roles/migraciones/topología del equipo. Copiar
   scripts/e2e/compose.owner-prepared.example.yml a tmp/e2e-owner.yml; completar
   variables y habilitar únicamente los consumers y storage revisados. Conservar
   acceptance=false, authorization=disabled y migraciones automáticas DEV 3 apagadas.
   !reset elimina nombre fijo de Dashboard en ese proyecto. Usar puertos libres
   loopback mediante override si los actuales están ocupados; nunca detener laboratorio.
3. Construir siete JAR: `mvn -B -ntp -f services/<modulo>/pom.xml verify`.
   Dashboard hoy falla dos tests de configuración: Gabriel debe corregirlos antes
   de declarar verde este gate. Nunca usar skipTests para avanzar.
4. Definir MVP_DB_PASSWORD, MVP_RABBIT_PASSWORD, E2E_MATCH_ID=e2e-<run> y las
   variables E2E_* requeridas en la plantilla. Validar antes de iniciar:
   `docker compose -p live-jvg-e2e-<run> -f docker-compose.yml -f tmp/e2e-owner.yml --profile integration-prepared config --quiet`.
   No imprimir config resuelto: contiene secretos. No reutilizar volúmenes.
5. Operador del entorno inicia SOLO brokers/base del proyecto nuevo, provisiona roles
   y ejecuta migraciones por propietario (control antes de worker); verifica grants,
   schemas y topología Rabbit. Estos pasos son del procedimiento aprobado de cada
   propietario: este harness no ejecuta DDL, provisionamiento ni migraciones.
6. Operador construye/inicia servicios del mismo proyecto y verifica readiness y
   ausencia de datos para el match de este run. Match State y Dashboard conservan
   sus migraciones propias al inicio; nunca apuntarlos a base compartida.
7. Definir LIVE_JVG_E2E_APPROVED_PROJECT=live-jvg-e2e-<run>,
   LIVE_JVG_E2E_WORKER_ROUTING_KEY=<binding aprobado> y opcionalmente
   LIVE_JVG_E2E_RABBIT_VHOST (default /). Ejecutar:
   `python scripts/e2e-harness.py execute-v1 --run <run> --compose-file docker-compose.yml --compose-file tmp/e2e-owner.yml`.
   Este comando publica eventos sintéticos Kafka y un comando simulado Rabbit;
   reinicia únicamente contenedores del proyecto validado. No cambia flags,
   acepta apuestas, crea leases ni borra recursos. Requiere Rabbit management en loopback.
8. Consultar result.json privado bajo tmp/live-jvg-e2e-<run> y logs propios. Ante
   fallo no repetir mismo run: conservar evidencia y usar nueva identidad después
   de corregir causa. No sobrescribir resultados ni limpiar volúmenes automáticamente.

Escenarios del ejecutor vía REST Producer -> Kafka: GOAL -> VAR_CHECK dirigido/general simultáneos ->
MATCH_CONTINUES dirigido -> GOAL_ANNULLED V1 -> última continuación. En V1 c1
produce CLEAR global; no reportarlo como cierre parcial V2. Comprueba scoreboard,
inbox Dashboard/Notification, accepted_event, archived_event y processed_event,
mercado SUSPENDED y cero ACCEPTED. Duplica GOAL y usa un nuevo evento barrera para
verificar que todos consumidores avanzaron. Envía comando simulado que debe quedar
BLOCKED. Reinicia seis consumidores y envía otra barrera, verificando persistencia
y consumo posterior. Evidencia SQL no certifica STOMP/UI ni respuesta LiveAlert REST;
Gabriel debe observar esos canales si se exige certificación completa.

Suites separadas: local=mvn verify; PostgreSQL=integration-postgres de Match State
con DurableIntegrationSafety y entorno 9F nuevo aprobado; brokers=integration-brokers
con variables/outage gate propios; DEV 3/Notification usan sus perfiles existentes.
No forzar estas suites sobre el Compose de este documento: sus guards exigen nombres,
puertos y bases distintos. Nueva prueba PostgreSQL está preparada, no ejecutada.
CI solo corre local y estático; no presenta gates PostgreSQL/brokers/E2E como verdes.

## Resultado de preparación local (16 noviembre 2026)

| Servicio | Resultado real disponible | Tests / skips |
|---|---|---|
| Producer | verify repetido, SUCCESS | 78 / 0 |
| Match State | verify tras cambios, SUCCESS | 111 / 0 |
| Dashboard | verify ejecutado, FAILURE | 150 / 0; dos fallos de configuración |
| Notification | verify previo en esta rama, SUCCESS; fuentes intactas | 103 / 0 |
| Archiver | verify previo en esta rama, SUCCESS; fuentes intactas | 77 / 1 |
| Suspension | verify previo en esta rama, SUCCESS; fuentes intactas | 131 / 1 |
| Worker | verify previo en esta rama, SUCCESS; fuentes intactas | 118 / 15 |

Éxitos adicionales: veinte fixtures/schema, YAML CI, resolved Compose con siete Java,
red/volúmenes/dependencias/flags, cinco pruebas offline del harness, py_compile y
git diff --check. Logs nuevos: tmp/next-<servicio>-verify.log. Logs previos y conteos
en REVIEW.md. No confundir tests reportados con ejecutados: DEV 3 incluye 17 skips.

Fallos: Dashboard LocalMvpConfigurationTest y ValidationPipelineTest, detallados en
TEAM-CONFIG.md. No se cambiaron fuentes/tests DEV 2 para hacerlos verdes. El workflow
conserva fallos sin continue-on-error, skipTests ni exclusiones nuevas.

Omisiones explícitas: nueva prueba PostgreSQL y suites externas no ejecutadas;
V2 durable no implementado sin contrato/versionado; imagen Docker no construida;
harness execute-v1 no ejecutado; CI remoto no ejecutado; E2E no certificado.
El siguiente paso es revisión de Gabriel/Valeria, no activar aceptación ni leases.

Inventario nuevo de esta fase: PreparedVarCorrelation y su test, ProposedContractFixtureTest;
ampliaciones MatchStateReplayTest/JdbcHistoryReplayTest/JdbcMatchStateStoreIntegrationTest;
scripts e2e-harness.py, test-e2e-harness.py, validate-compose.py y plantilla e2e;
validador schemas/fixtures y job estático ampliados; contratos locales actualizados
y estos tres documentos. Infraestructura de la fase previa permanece en el diff.

Git: dev1/architecture-closure, HEAD 2e53fdece51e6e4ec08782dec8f31b30630c805e.
Cinco eliminaciones staged previas conservadas exactamente; ningún add/commit/push.
Recomendación: apto para compartir diff o PR borrador coordinado, NO para PR listo
para merge mientras Dashboard falle y falten aprobaciones semánticas/operativas.
