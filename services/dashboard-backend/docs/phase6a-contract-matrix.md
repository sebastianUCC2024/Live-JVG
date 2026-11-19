# Fase 6A — Matriz de contratos y compatibilidad

**Estado: propuesta para revisión conjunta; no aprobación ni implementación.**
Inspección local del 2026-11-16 (America/Bogota), rama
`feature/Gabo-react-integration`, HEAD `556eefb54755602ebacd5e1e1a8416490420dfaf`.
No se consultó GitHub ni se actualizaron referencias. El alcance temporal exacto
se registra en [decisiones y Git](phase6a-decisions-and-git.md).

Este documento enlaza la [propuesta VAR](phase6a-var-v2-proposal.md), los
[criterios de aceptación](phase6a-acceptance-criteria.md) y el
[plan de integración aislada](phase6a-isolated-integration-plan.md).

## 1. Estado de los contratos

I = interfaz implementada observada; D = documentación local; P = propuesta sin
aprobar; A = definición formal ausente. Una interfaz I no implica certificación E2E.

| ID | Contrato y transporte | Productor → consumidores | Estado / versión real |
| --- | --- | --- | --- |
| C01 | MatchEvent JSON UTF-8, Kafka `match_events`, key=match_id | Live Feed Producer → Match State y Historical Archiver, grupos independientes | I/D; sin campo de versión en el evento |
| C02 | ScoreUpdate JSON UTF-8, Rabbit topic `live_updates`, routing `score.{matchId}` | Match State → Dashboard y Notification mediante colas independientes | I/D provisional; sin versión ni secuencia de origen en el JSON |
| C03 | Catálogo, detalle y snapshot REST; STOMP `/ws` | Dashboard → React; consumidor estático para REST | I/D; snapshot añade stream_id/revision, no versión de contrato |
| C04 | LiveAlert REST `GET /api/alerts` | Notification → React por polling | I/D local; contract_version literal `1.0` |
| C05 | POST de simulación y errores recuperables del PR #18 | Cliente estático/API → Dashboard → Producer; respuesta inversa | I/D; mismo POST, sin almacenamiento/API nueva de recuperación |
| C06 | Consulta de procesamiento por event_id | Dashboard inbox → cliente estático | I/D; boolean processed, no confirmación directa Kafka |
| C07 | Archivo de MatchEvent e historial REST | Kafka → Historical Archiver; API propia de DEV 3 | I/D; formato observado local, no dependencia del dashboard |
| C08 | Reglas P2 de correlación VAR | Futuro motor Match State → consumidores del estado agregado | P; formato sin cambios propuestos, semántica pendiente |

`contracts/events/match-event.schema.json`, `score-update.schema.json`,
`live-alert.schema.json` y `betting-command.schema.json` tienen **0 bytes** en este
checkout (A). No sirven como esquemas validables. No se editaron ni se propone
tratar los DTO locales como aprobación automática del equipo.

## 2. C01 — Evento deportivo

Fuente: [MatchEvent del productor](../../live-feed-producer/src/main/java/com/live_jgv/live_feed_producer/domain/model/MatchEvent.java),
[entrada de Match State](../../match-state-service/src/main/java/com/live_jgv/matchstate/infrastructure/kafka/MatchEventMessage.java)
y [decoder del archivador](../../historical-archiver/src/main/java/com/live_jgv/archiver/infrastructure/JsonEventDecoder.java).

| Campo | Tipo observado | Obligatoriedad / validación |
| --- | --- | --- |
| event_id | string no blanco | Obligatorio en Kafka; opaco, no exige UUID; no sustituir durante recuperación |
| match_id | string no blanco | Obligatorio; key Kafka igual a este valor; pertenencia al catálogo según servicio |
| event_type | string enum | GOAL, VAR_CHECK, GOAL_ANNULLED, MATCH_CONTINUES |
| team_id | string o null | Obligatorio/no blanco para GOAL; nullable para otros; el simulador restringe equipos al partido |
| minute | entero Java int | Obligatorio, 0..130, límite de simulación existente, no regla universal de fútbol |
| timestamp | string ISO Instant | Obligatorio, admite nanosegundos; no establece orden causal |
| reference_event_id | string o null | Obligatorio/no blanco para GOAL_ANNULLED; significado depende del tipo y política VAR |

El productor serializa siete campos, incluidos nulls. Algunos consumidores aceptan
ausencia de campos nullable. El dominio histórico permite ciertos valores de
referencia que la política de publicación actual rechaza (p. ej. blancos para
tipos que no requieren referencia). No endurecer la lectura histórica sin acuerdo.
El simulador HTTP permite omitir ID y timestamp **juntos** para generarlos; no es
permiso para omitirlos en Kafka ni para regenerarlos al reintentar.

El archivador valida UTF-8, clave=partido, duplicación de claves JSON, tokens
residuales y campos desconocidos. Conserva payload/headers originales y posición
Kafka. No interpreta reglas de revisión: aceptar/archivar un evento no significa
que Match State lo haya aplicado. Su grupo por defecto es historical-archiver;
Match State usa match-state-service. No deben compartir grupo consumidor.

## 3. C02 — Actualización autoritativa de marcador

Fuente: [ScoreUpdate de Match State](../../match-state-service/src/main/java/com/live_jgv/matchstate/domain/model/ScoreUpdate.java),
[decoder dashboard](../src/main/java/com/live_jgv/dashboard/infrastructure/amqp/ScoreUpdateMessageDecoder.java),
[codec notification](../../notification-backend/src/main/java/com/live_jgv/notification/infrastructure/json/ScoreUpdateCodec.java).

| Campo | Tipo | Requisito observado |
| --- | --- | --- |
| match_id, event_id | strings no blancos | Identifican partido y evento aplicado; no exigen UUID |
| home_score, away_score | enteros Java int | Obligatorios, 0..2147483647 en consumidores Java |
| event_type | enum C01 | Evento aplicado; no equivale siempre a identidad de revisión |
| updated_at | string ISO Instant | Hora de procesamiento de Match State; no es timestamp del evento original |
| var_status | CLEAR o IN_PROGRESS | Estado agregado autoritativo; no autorización de apuestas |
| reference_event_id | string o null | Nullable/ausente aceptado por codecs; anulación requiere referencia no blanca |

No contiene nombres/IDs de equipos, jugador, minuto, lista de revisiones, versión
de reglas, stream_id o revisión de origen. Dashboard y Notification no deben
fabricarlos. Los codecs rechazan campos desconocidos; añadir schema_version u otro
campo sería una evolución potencialmente incompatible, aunque sea aditiva.
Notification limita el body a 65536 bytes y rechaza claves JSON duplicadas/tokens
residuales; el decoder dashboard también es estricto con tipos, duplicados y
tokens residuales. Esos límites no sustituyen un máximo compartido aprobado.

Publicación Rabbit persistente, exchange topic durable y message_id=event_id.
Dashboard durable usa por defecto mvp-dashboard-scores y Notification
notification.score-updates; sus DLX/DLQ son propias. Los nombres/puertos son defaults
de código, no asignaciones aprobadas para laboratorio.

## 4. C03/C06 — API de dashboard y recuperación

GET `/api/matches` devuelve filas con match_id, home_team_id, away_team_id,
home_name, away_name, home_score, away_score, var_status, last_event_id y updated_at.
Los dos últimos son nullable antes de eventos. GET `/api/matches/{id}` añade arrays
events (hasta 30 recientes por secuencia descendente) y active_goals. No pagina el
historial; active_goals no es una lista de revisiones VAR.

GET `/api/matches/{id}/score` devuelve C02 más stream_id:string y revision:entero
positivo; 404 si no hay snapshot. Stream durable = época persistida + partido;
revision procede de la secuencia del inbox, no de un contador del productor.
La UI exige Number.isSafeInteger: el rango completo BIGINT SQL no es representable
con el DTO numérico del navegador; acordar límites antes de excederlo.

STOMP nativo `/ws`: suscripción `/topic/matches/{id}/score`, recuperación mediante
SUBSCRIBE `/app/matches/{id}/snapshot`, respuesta `{available,snapshot}`. Un snapshot
no disponible usa available=false y snapshot=null. No hay WebSocket de Notification.
La respuesta y sus tipos están en
[MatchSnapshotResponse](../src/main/java/com/live_jgv/dashboard/infrastructure/web/MatchSnapshotResponse.java).

GET `/api/matches/{id}/events/{eventId}` responde `{processed:boolean}` consultando
el inbox comprometido. false no demuestra rechazo en Kafka ni permite otro ID.
Memory no ofrece el mismo catálogo/historial durable. React obtiene nombres por
REST; selección manual/memory y demo no deben activar Notification implícitamente.

## 5. C04 — Alertas y cursor

Fuente: [AlertController](../../notification-backend/src/main/java/com/live_jgv/notification/infrastructure/web/AlertController.java)
y [validador React](../../dashboard-frontend/src/types/alert.ts).

GET `/api/alerts?match_id={id}&limit=50`, incremental con `after={cursor}`.
Envelope: items:array, next_cursor:string opaco, has_more:boolean. Backend permite
limit 1..100 (default 50); este cliente pide 50 y valida páginas de hasta 50.

Cada item requiere contract_version="1.0", alert_id UUID, match_id, alert_type
(enum C01), source_event_id, source_updated_at, created_at, reference_event_id
(nullable; requerido para anulación), home_score, away_score, var_status y content.
content contiene locale="es", title no vacío (máximo 120), message no vacío
(máximo 500). Las fechas son strings Instant; el cliente de alertas comprueba
calendario, hora y hasta nueve decimales. Timestamps se presentan en America/Bogota.

GOAL/VAR_CHECK/GOAL_ANNULLED generan alertas; MATCH_CONTINUES solo registra inbox.
La primera página es la ventana reciente, devuelta ascendente; has_more inicial
es false. El incremental drena secuencia ascendente, no orden por created_at.
Página vacía puede avanzar cursor. No hay paginación hacia el pasado.
400 = consulta/cursor inválido; 409 = época distinta o checkpoint adelantado;
503 = almacenamiento deshabilitado/no disponible. Cursor v1 interno contiene
época, partido y secuencia; no es token de autenticación ni versión de LiveAlert.
La consulta limita match_id a 1024 caracteres y el cursor a 4096. Los clientes
deben conservar el cursor opaco, no fabricar su contenido ni usarlo como secreto.

## 6. C05/C07 y compatibilidad histórica

El [contrato HTTP PR #18 y su proxy](phase5d-publication-recovery-and-var-readiness.md)
conserva 202 sin wrapper; errores 500/EVENT_PREPARATION_FAILED y
503/PUBLICATION_UNCONFIRMED con error seguro y event completo cuando se recupera.
Gateway valida correspondencia, preserva status y filtra datos internos. Timeout
o pérdida de respuesta no recuperan identidad desde el servidor. No hay retries
automáticos de publicación en el cliente ni almacenamiento nuevo del gateway.

Archiver expone GET `/api/history/matches/{id}/events?page=0&size=50` (size 1..200),
con content, page, size, total_elements y total_pages. Entry añade sequence y
received_at al evento. No usa cursor de alertas ni stream_id. **No es fuente de
marcador actual para DEV 2**: no introducir consultas a su API para reconstruir dominio.

La igualdad de duplicados no es idéntica en todos los servicios: Notification
compara ScoreUpdate deserializado (Instant normalizado); Dashboard compara árboles
JSON del payload; Archiver canonicaliza null/ausencia nullable pero preserva la
representación textual del timestamp. Conservar la publicación original evita
conflictos de restauración; normalizar/recrear un payload no es una operación neutra.

Propuesta a aprobar (sin aplicarla): congelar fixtures actuales, documentar campos
requeridos/nullable, códigos HTTP y límites, completar esquemas por sus responsables
y ejecutar compatibilidad antes de evolucionarlos. No exigir UUID a event_id histórico.
Para nuevos IDs de partidos, acordar el subconjunto operativo de routing/React:
Notification exige ASCII [A-Za-z0-9_.-]+ y score.{id} <=255 bytes UTF-8 (hasta
249 caracteres ASCII). Eso no es hoy una restricción universal del dominio.
