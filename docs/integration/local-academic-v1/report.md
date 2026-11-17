# Integración local académica V1 — resultado verificable

Estado: integración local realizada; NO finalizado ni certificado E2E. No se publicó esta rama.

## Historial y protección

- Worktree: `/private/tmp/live-jvg-local-academic-v1`.
- Rama exclusiva: `integration/local-academic-v1`.
- Base: `257662cc6fb5c300e01e5ad4ad967566dd60af88`.
- DEV1: `e02e2f73bb5e24d2ba5bcbe8b2050f3476f48ce2` (13 archivos; contiene 21eed1d).
- DEV3: `64675e28c7afc7e5b84854f6a15587235ffcbe23` (48 archivos congelados).
- DEV2: `7c175241231db9a62181f37e9e6f64a158696859` (37 archivos).
- Merges locales, sin fast-forward, en orden DEV1 → DEV3 → DEV2. No hubo conflictos Git.
- Los tres commits se conservan como ancestros. No se reescribieron ni alteraron ramas de revisión ni dev.
- El SHA de entrega se obtiene con `git rev-parse integration/local-academic-v1`; se comunica tras el commit final.

## Correcciones reales en la integración

1. P1: Compose base/overlay explicitan los cuatro flags académicos OFF. El preflight legado exige false
   en fase pasiva y activa; rechaza ausentes, true, booleanos y otros valores. Verifica los valores de cada
   réplica y rechaza aliases en minúsculas/dotted y overrides ACADEMIC_/SPRING_ no declarados (incluido SPRING_APPLICATION_JSON).
   También protege los nuevos flags de generación/publicación Fanout. No se llama a infraestructura al validar fixtures.
2. ORDER02: el publicador durable transporta la application_seq ya persistida en score_outbox como cabecera
   AMQP integral. El JSON ScoreUpdate V1 de ocho campos NO cambia. El envío directo/memory no inventa secuencia.
   Dashboard incorpora `dashboard.score.sequence-required` opt-in, false por defecto. Con V3 revisada/aplicada
   y el modo autorizado, exige cabecera, continuidad desde 1, coherencia de identidad y dedup dentro de la
   transacción con lock por partido. Una salida antigua desconocida o con hueco no toca marcador/inbox y pasa
   por la recuperación DLQ ya existente. No se ordena por timestamps.
3. Dashboard V3 añade source_application_seq nullable e índice único por partido/secuencia. No hay backfill
   inventado; una inbox legacy requiere revisión y replay antes del modo estricto. Las actualizaciones REST/STOMP
   conservan stream_id/revision y sus DTO; React sigue usando esos guardas. No se ejecutó la migración.
4. Worker académico añade una identidad aleatoria por instancia en metadatos auditados (no en AcademicCommand)
   para preparar la observación de dos consumidores. Readiness distingue el listener académico del anterior,
   rechaza modos simultáneos y nunca concede autoridad. No inicia listeners desde el healthcheck.
5. Generador común actualizado de 14 a 17 migraciones, tablas nuevas y SELECT pasivos. La propuesta 70 separa
   permisos académicos/sports de la propuesta legacy 60: no da escritura sobre operaciones, grants, leases o
   funciones de autoridad al Worker. No aplicar 60 en el ensayo académico. Se corrigió el nombre UTF-8 del
   equipo y la escritura de archivos compatible con Python 3.9; `--output-dir` prepara otras propuestas sin
   sobrescribir las revisadas. Se regeneró texto/hash; no se ejecutó SQL.
6. Nuevo `scripts/academic-e2e.py`: plan offline, check-config offline con modelo JSON proporcionado y ejecución
   futura bajo doble autorización explícita. Reutiliza el harness y el endpoint real del Producer. Observa GOAL,
   VAR_CHECK, GOAL_ANNULLED y MATCH_CONTINUES, Fanout, comandos académicos y una barrera tras duplicado Kafka.
   No usa el comando de apuestas antiguo. Los duplicados reutilizan el wire devuelto por Producer, no una
   representación temporal equivalente que pudiera provocar conflicto de identidad por formato de timestamp. El modo ON exige roles Rabbit dedicados, vhost académico distinto,
   Notification en cola Fanout propia, modo estricto de secuencia y operación/autoridad desactivadas.
   Se incluyen un overlay ON **propuesto** y pruebas; no se ejecutó ese overlay ni el ensayo.

## ORDER02: garantía y límite

La protección nueva está implementada y probada offline, pero aún NO habilitada ni certificada con PostgreSQL/Rabbit.
La cabecera solo es autoritativa bajo un único dueño del estado y permisos que impidan otros publicadores.
Un salto se rechaza y necesita custodia/replay ordenado; no se promete reparación automática de reordenamiento.
El consumidor legado sin cabecera y la proyección memory siguen sin garantía contra un evento antiguo desconocido.
Restauración parcial, reinicio de secuencia en otro origen, historial legacy y épocas de productor requieren revisión.
ORDER02 global permanece ABIERTO. No basta activar una cabecera o confiar en updated_at.

## Pruebas reales de esta tarea (offline)

| Módulo | Exitosas | Omitidas | Fallos/errores finales |
|---|---:|---:|---:|
| live-feed-producer | 78 | 0 | 0 |
| match-state-service | 129 | 0 | 0 |
| dashboard-backend | 166 | 0 | 0 |
| historical-archiver | 80 | 2 | 0 |
| betting-suspension-service | 157 | 3 | 0 |
| betting-worker | 142 | 17 | 0 |
| Total ejecutable | 752 | 22 | 0 |
| notification-backend | NO EJECUTADO | No determinado | Maven bloqueado antes de pruebas |

Maven verify incluye test/package. Notification conserva su POM real Boot 4.1.2: parent ausente en caché offline.
Una copia diagnóstica externa con Boot4.1.1 cacheado tampoco pudo resolver todos sus JAR offline; no sustituye
la prueba objetivo y NO cambió el POM integrado. No se bajó de versión ni se declaró verde su suite.
El fixture JDBC de publicación inicialmente omitía la columna de secuencia nueva; se corrigió y la suite pasó.
Los logs iniciales fallidos se conservan, además de los finales.

- React: 143 pruebas / 15 archivos; TypeScript + Vite build exitosos, usando dependencias existentes sin instalar.
- Python: 40 harness + 11 académico + 5 runtime shell + 26 generador común + 4 generador aislado = 86 exitosas.
- Scripts académicos/Python compilan. Validación YAML estática en las pruebas Java; no equivale a Docker Compose config.
- `validate-dev1-contracts.py` no pudo ejecutarse por ausencia de fastjsonschema; no se instaló ni descargó.
- El puente MVC Notification → guardas TS requiere su suite/fixture vigente; no se simula ese resultado.
- git diff --check y whitespace de archivos nuevos se revisan antes del commit. Evidencias en `evidence/`.

## Matriz de requisitos académicos

PASS OFFLINE indica implementación y pruebas locales; no significa PASS con infraestructura.

| Requisito | Responsable / evidencia | Estado | Validación pendiente |
|---|---|---|---|
| Java17, records, dominio y puertos separados | módulos Java, tests de dominio/arquitectura | PASS OFFLINE en seis módulos | Notification POM objetivo |
| Kafka match_events, clave match_id | Producer y Match State, suites de codec/publicación | PASS OFFLINE | topic, clave y offsets reales |
| GOAL / VAR_CHECK / GOAL_ANNULLED / MATCH_CONTINUES | dominio productor/estado, fixtures | PASS OFFLINE V1 | secuencia real |
| Gol y anulación corrigen 1-0 → 0-0 | pruebas Match State y plan académico | PASS OFFLINE | marcador real observado |
| Referencia al gol original | reference_event_id en wire y evidencia académica | PASS OFFLINE del nombre existente | el enunciado dice annuls_event_id; no existe alias literal aprobado |
| ScoreUpdate Topic live_updates | outbox/publicador y Dashboard | PASS OFFLINE | binding, confirms, ACK y recuperación reales |
| Orden y duplicados | cabecera/application_seq + Dashboard V3 opt-in | PARCIAL | migración, replay, ACL; ORDER02 legado abierto |
| WebSocket/STOMP/REST Dashboard | suite Dashboard y frontend | PASS OFFLINE | conexión, reconexión y navegador reales |
| React muestra marcador/VAR/alertas | 143 tests y build | PASS OFFLINE | captura con servicios y datos reales |
| Fanout live_alerts | outbox independiente, contrato y Notification DEV2 | PARCIAL | Maven Notification objetivo y Fanout real |
| Work Queue suspensión/reanudación | AcademicCommand V1, Worker, outbox | PASS OFFLINE | entrega real, dos instancias, ACK después de COMMIT |
| Apuestas seguras | policy BLOCKED, acceptance=false, authority=disabled | PASS OFFLINE | SELECT global cero ACCEPTED/grants/leases en ensayo |
| PostgreSQL histórico/auditoría | Archiver y adapters JDBC, pruebas locales | PARCIAL | V2/V3/V4, ownership, restricciones y recuperación reales |
| Docker multietapa no root | Dockerfile y tests Compose/runtime | PASS ESTÁTICO | build de las siete imágenes |
| JVM/límites/dos Workers | tests shell/YAML y preflight por réplica | PASS OFFLINE | enforcement y consumo real de CPU/RAM |
| JUnit5/Mockito/TDD | suites y evidencias RED/GREEN conservadas | PASS OFFLINE parcial | suite Notification objetivo |
| Reentrega, DLQ, reinicio, outbox | pruebas de fallos y plan de observación | PASS OFFLINE | fallos inducidos autorizados, persistencia física |
| Contratos compartidos vacíos | betting-command/match-event/score-update.schema.json | PENDIENTE DE APROBACIÓN | no se publican como aprobados por inferencia |
| E2E integral académico | harness adicional y guía | PREPARADO, NO EJECUTADO | autorización de infraestructura y activación |

## Próximos bloqueos y autorizaciones exactas

1. Autorizar resolución de dependencias Maven del POM Notification real y del validador Python, sin cambiar versiones
   por disponibilidad de caché. Repetir verify/contratos; no hay evidencia de un fallo de código en esa suite.
2. Revisar el nuevo metadato application_seq, Dashboard V3 y los contratos públicos aún vacíos; decidir la equivalencia
   del término académico annuls_event_id con reference_event_id, sin introducir un alias incompatible automáticamente.
3. Aprobar exclusivamente recursos NUEVOS: proyecto/puertos libres, red, volúmenes, PostgreSQL17, Kafka y RabbitMQ;
   atestación de ausencia de reutilización. No usar el PostgreSQL de laboratorio anterior ni recursos 7E.
4. Aprobar roles/owners/migradores, 17 migraciones, cross-owner y SELECT pasivos. Ejecutarlos exige autorización separada.
5. Aprobar arranque pasivo con el overlay OFF y comprobar cero escrituras/consumo/publicaciones/declaraciones.
6. Aprobar vhosts/ACL y DML 70, luego el escenario académico ON con consumidores de prueba. Aceptación sigue false,
   autoridad disabled, cero operaciones/grants/leases. No es permiso para apuestas reales.
7. Autorizar por separado duplicados Rabbit, DLQ/replay, fallos/reinicios y mediciones de recursos en infraestructura exclusiva.
8. Publicar esta integración, abrir PR o fusionarla a dev requieren autorización posterior. Nada de ello se hizo.

La guía `demonstration.md` contiene el procedimiento preparado. La tarea llega a estos límites operativos;
la ausencia de infraestructura ejecutada y de validación Notification impide declarar el proyecto finalizado.
