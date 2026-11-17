# DEV 1: cierre offline V1 y propuesta E2E

Fecha: 16 noviembre 2026. Base local y origin/dev: c65a6f017641e9bd9f4cc71b00bd484da17029e6.
PR #22 incorporado (7788295); PR #24 corrige tests Dashboard; PR #23 revertido por #25;
PR #26 prepara DEV 3 y PR #27 incorpora 2d19cd7 con SELECT completos de readiness.
No pull, merge, commit ni push en esta ejecución; solo fetch de refs.

## Diagnóstico actual

| Estado | Evidencia |
|---|---|
| RESUELTO | Producer verify: 78 tests, cero fallos/errores/skips. |
| RESUELTO | Match State verify final: 112 tests, cero fallos/errores/skips. |
| RESUELTO | Dashboard verify: 150 tests, cero fallos/errores/skips; el fallo previo era documentación obsoleta. |
| RESUELTO | 18 tests offline del harness; 20 fixtures/schema; CI YAML; Compose base y propuesta mediante config, sin daemon ni arranques. |
| RESUELTO | Base/tópico dejaron de estar fijados a live_jvg/match_events; modo legacy preservado. |
| RESUELTO | Archiver V2 ya alinea identidad (match_id,event_id); documentación actualizada. |
| BLOQUEADO | No hay autorización actual para Docker up, PostgreSQL/Kafka/Rabbit, migraciones, publicaciones ni restart. Usuario confirma modo offline. |
| BLOQUEADO | V2 externo, GOAL_ANNULLED con doble identidad, discriminador y política por partido necesitan aprobación Gabriel/Valeria. |
| BLOQUEADO | Fuente/checkpoint/frescura y emisión de leases no aprobados; no se implementa bypass. |
| PENDIENTE IMPLEMENTABLE TRAS APROBACIÓN | Aprovisionar recursos NUEVOS, migrar con propietarios, activar consumers por separado y ejecutar ensayo V1 con BLOCKED. |

Logs NUEVOS: tmp/review-v1-live-feed-producer.log, tmp/review-v1-match-state-service.log,
tmp/review-v1-dashboard-backend.log. Repetidos en c65a6f0; no cifras reutilizadas.
Generador DEV 3: cuatro tests offline repetidos correctamente. No se repitió Maven DEV 3: su evidencia incorporada
reporta 330 exitosas/20 omitidas, sin certificar E2E real. Notification no se volvió a
verificar en esta fase. Suites PostgreSQL/brokers no ejecutadas.

## Manifiesto a aprobar

Archivo: scripts/e2e/live-jvg-e2e-5c84548cf006.proposed.json.
No se encontró copia local del manifiesto externo mencionado por el usuario. Esta propuesta
alinea common-v1 con v1-e2e-readiness.md publicado por DEV 3. Conserva SHA-256 del
generador normalizado UTF-8/LF, portable entre Windows y CI Linux.
No sustituye el original ni se declara aprobada por coincidir con un generador.

| Recurso | Nombre propuesto |
|---|---|
| Proyecto nuevo | live-jvg-e2e-5c84548cf006 |
| Base común | live_jvg, NUEVA dentro del proyecto aislado |
| Archiver común | schema archiver en live_jvg; alternativa standalone en dev3_it_5c84548cf006_archive |
| Tópico común | match_events dentro del Kafka NUEVO |
| Exchange / cola | dev3_it_5c84548cf006_commands / dev3_it_5c84548cf006_worker |
| Routing / vhost común | dev3_it_5c84548cf006.command / / |

Roles control/worker y destinos se derivan de DEV 3; base/tópico/vhost comunes siguen
su propuesta publicada, no su SQL standalone. Tríada Archiver, roles adicionales
y puertos requieren revisión nueva. El generador DEV 3 no
aprovisiona esos recursos adicionales. No ejecutar su SQL sin la autorización del servidor nuevo.
Puertos loopback propuestos: PG 55991, Kafka 19991, Rabbit 56991/15991, Producer 18191,
Dashboard 18190. No se verificó ocupación ni se usaron esos puertos; solo config estático.

Override: scripts/e2e/compose.manifest-proposed.yml. Adapters, storage Notification,
publicación Suspension, aceptación, autoridad y V2 quedan OFF. Migraciones automáticas
DEV 3 permanecen apagadas; Match State/Dashboard conservan sus políticas existentes.
El override requiere provisionamiento/revisión de roles antes de cualquier arranque.

## Cambios funcionales

- --manifest parametriza DB principal, DB Archiver, tópico, exchange, cola, routing y vhost;
  rechaza nombres ajenos al run. observe consulta Archiver en su propia DB mediante SELECT.
- Preflight contrasta config con labels/entorno/montajes/redes reales y rechaza overrides,
  brokers/DB externos, puertos públicos y reemplazos de contenedor antes del restart.
- --timestamp permite planes reproducibles. Ocho fixtures V1 incluyen revisión atrasada y
  continuación sin referencia. Test Java prueba aplicación, duplicados, conflicto y replay
  con el agregado V1 real, preservando marcador y cierre global histórico.
- Compara timestamps equivalentes sin perder nanosegundos; barreras/esperas usan número
  dinámico de eventos. Preserva plan/resultados y registra claim exclusivo por ejecución.
- Validador offline resuelve override con secretos ficticios solo en memoria, comprueba
  coherencia con generador DEV 3 y se añade al job CI estático existente.

## Aprobaciones exactas restantes

1. Gabriel: esquema/rol Notification, Dashboard, bindings score.<match>, DLQ, snapshots/UI y
   revisión del override. MATCH_CONTINUES no genera LiveAlert; sí deja inbox SUPPRESSED.
2. Valeria: cotejar manifiesto original, DB común control/worker, DB Archiver, roles separados,
   V1/V2 SQL Archiver/control/worker, grants/readiness, source IDs/grupos y topología temporal.
3. DBA/operador y equipo: aprobar proyecto NUEVO, host/puertos/volúmenes, ejecución concreta de
   Docker y migraciones, activación de consumers/storage y ensayo con publicaciones/reinicio.
4. Gabriel y Valeria: aprobar por fila CONTRACT-DECISIONS.md antes de conectar V2. No es
   requisito inventar V2 para ensayar V1; mantener V2 y aceptación OFF durante ese ensayo.
5. Autoridad/leases y aceptación requieren otra aprobación/certificación; fuera del ensayo V1.

Todavía no existe evidencia nueva de entrega Kafka/Rabbit, transacciones PostgreSQL reales,
ACK/offset, reinicio de contenedores ni BLOCKED real. Las pruebas offline no los sustituyen.
La observación futura BLOCKED con autoridad disabled probará no aceptación, sin demostrar
que la suspensión fue la causa. No se declara E2E certificado.

## Git y publicación

Cinco eliminaciones staged anteriores conservadas. Índice SHA-256 inicial/final:
011ACE2C55B081AEC12B699D6371D4D9A977903EC99CC585A6E72026C868C8C7.
Ningún módulo DEV 2/3 ni contracts/events modificado. Los 14 archivos DEV 1 se seleccionan
en un índice temporal; el índice original conserva solo las cinco eliminaciones.
Se prepara diff/PR para revisión, sin crear commit, push o PR remoto sin autorización.

## Matriz DEV 1 ↔ DEV 3 (2d19cd7)

| Frente | Compatibilidad offline / diferencia / gate pendiente |
|---|---|
| Proyecto/aislamiento | Coincide live-jvg-e2e-5c84548cf006; redes/volúmenes propios, sin nombre fijo de contenedor. Existencia real no comprobada. |
| Base/Archiver | common-v1: live_jvg NUEVA y schemas separados. standalone conserva dev3_it_* y Archiver independiente. No sustituir nombres del SQL standalone. |
| Roles | Owners NOLOGIN, migradores y runtime separados. Proponer tríada Archiver y runtime MatchState/Dashboard/Notification; faltan DDL/grants de sus propietarios. Runtime sin ownership ni DDL. |
| SELECT readiness | Control: lectura COMPLETA authorization_authority y authorization_lease. Worker: lectura COMPLETA operation_authorization. Permiten SELECT * LIMIT 0 y también leer filas; no son permisos solo de metadatos. Sin nuevas escrituras ni emisor de leases. |
| Migraciones | Archiver V1/V2; control V1/V2 ANTES de worker V1/V2; owner/historial propio. MatchState V1/V2/V3, Dashboard V1/V2 y Notification V1 por sus dueños. No aplicadas. |
| Kafka/replay | match_events, key=match_id; grupos exclusivos dev3_it_5c84548cf006_archive/control/match_state. Source <project>/kafka-log-v1 estable tras restart. DB vacía+offsets avanzados no reconstruye histórico. DEV 1 replay por application_seq; sin reinterpretación V2. |
| Rabbit estado | live_updates topic, score.e2e-5c84548cf006 y colas Dashboard/Notification distintas. DLQ/ACL pendientes Gabriel. No prueba autoridad. |
| Rabbit comandos | Exchange/queue/routing dev3_it_* del manifiesto, vhost / solo en Rabbit NUEVO. Source Worker <project>/rabbit-commands-v1; publicación Suspension OFF y destino outbox separado pendiente. |
| Puertos/volúmenes | Loopback: propuesta 55991/19991/56991/15991/18191/18190. Volúmenes con prefijo del proyecto; sin external/bind. Ocupación/instalación no comprobadas. |
| Variables/secretos | E2E_* separa DB/tópico/roles/grupos y source Kafka/Worker. Passwords ficticias solo para config offline; secretos reales privados. No activar perfiles dev3-integration ni dev3-authority. |
| Worker BLOCKED | Acceptance=false, authorization=disabled, consumers OFF. No hay operación BLOCKED real ahora; futuro ensayo exige fila BLOCKED, cero ACCEPTED/grants. Disabled no prueba causalidad de suspensión. |
| Límite | Generador, cuatro tests, evidencia y readiness publicados revisados. Manifiesto privado /private/tmp/live-jvg-dev3-v1-readiness/environment-manifest.json inaccesible: NO compatibilidad completa certificada ni aprobación implícita. |

SELECT completos y funciones SECURITY DEFINER requieren revisar ACL/owners/membresías
en el servidor nuevo antes de arrancar. No se ejecutó SQL ni se comprobaron permisos reales.
