# Fase 6A — Propuesta de aceptación VAR V2

**PENDIENTE DE APROBACIÓN DEV 1 / DEV 2 / DEV 3.** No cambia código, contratos,
reglas, datos ni estado de aprobación. Base y contexto: [matriz de contratos](phase6a-contract-matrix.md).
Fuente deportiva: [propuesta D1.4A](../../match-state-service/docs/D1_4A_REGLAS_VAR_PROPUESTA.md),
política P2 recomendada por DEV 1, todavía no oficial.

## 1. Separar formato, reglas y autoridad

Se propone conservar los campos MatchEvent/ScoreUpdate existentes. Compatibilidad
de lectura no implica que el motor actual implemente P2 ni que el simulador pueda
emitir todos sus escenarios. El agregado de revisiones pertenece a Match State.
Dashboard y Notification solo leen marcador/estado autoritativos; no cuentan,
abren ni cierran revisiones a partir de un historial parcial.

## 2. Matriz de interpretación

| Elemento | Comportamiento V1 observado / lectura DEV 2 | P2 propuesto | Decisión para consumidores |
| --- | --- | --- | --- |
| event_id | Identidad de evento, deduplicación por partido/contenido | VAR_CHECK.event_id además identifica revisión | Mantener como string opaco; otro evento no hereda automáticamente identidad de revisión |
| GOAL | Añade gol; conserva estado VAR vigente | Igual, no cierra revisiones | Mostrar scores recibidos incluso con IN_PROGRESS; no recalcular goles |
| VAR_CHECK | Permite revisión general/null o referencia a gol activo; pasa a IN_PROGRESS | Abre revisión identificada por event_id; no dos abiertas distintas sobre un mismo gol | referencia sigue siendo gol, no el ID de revisión recién creada |
| GOAL_ANNULLED | Referencia a gol activo; anula y actualmente deja CLEAR global | Anula ese gol y cierra solo su revisión asociada; puede quedar IN_PROGRESS | Aceptar snapshot con anulación + IN_PROGRESS sin anunciar cierre global |
| MATCH_CONTINUES | Actualmente deja CLEAR sin correlacionar la referencia | Con revisiones pendientes exige referencia al VAR_CHECK que cierra; cierra solo esa revisión | Referencia no debe interpretarse como gol; no emitir alerta de cierre nueva sin acuerdo |
| reference_event_id | String nullable con semántica por tipo | Gol al abrir/anular; revisión en continuación dirigida | No cambiar tipo JSON ni imponer un único significado global |
| IN_PROGRESS | Indicador global, sin lista autoritativa | Al menos una revisión sigue activa | Banner «Revisión VAR en curso»; no deducir número de revisiones |
| CLEAR | Estado deportivo agregado sin revisión activa | Ninguna revisión activa | No equivale a mercado OPEN ni a permiso para ejecutar apuestas |
| Revisiones simultáneas | V1 no conserva colección; cierre parcial puede despejar todo | Se admiten generales y sobre goles distintos, con identidad independiente | ScoreUpdate no expone colección; no reconstruirla desde últimos 30 eventos |
| Cierre parcial | Formato aceptable por codecs actuales; motor V1 no garantiza P2 | Continuación/anulación puede conservar IN_PROGRESS | Mostrar corrección de marcador y mantener indicador; sin cierre global inferido |
| Histórico V1 | Se reproduce con reglas V1; valores históricos no se normalizan silenciosamente | Nuevos historiales V2 opt-in después de aprobación/versionado | Leer ambos formatos actuales, sin reinterpretar pasado ni rehacer outbox |

## 3. Escenarios para aprobación, no resultados ejecutados

Notación: g1/g2 goles; v1/v2/vg revisiones; a anulación; c continuación. Las
flechas indican reference_event_id. Son secuencias simbólicas, no JSON completos.

| Secuencia propuesta | Estado final esperado si se aprueba P2 |
| --- | --- |
| g1, v1→g1, a→g1 | Gol anulado; CLEAR si no queda otra revisión |
| g1, g2, v1→g1, v2→g2, a→g1 | Marcador con g2; IN_PROGRESS por v2 |
| Secuencia anterior, c→v2 | Mismo marcador; CLEAR |
| vg general, g1, a→g1 | Gol corregido, IN_PROGRESS por vg |
| v1, c→v1, v2, continuación nueva→v1 | El cierre tardío no cierra v2; clasificación según decisión DEV 1 |
| c sin referencia y sin revisiones | Continuación deportiva aceptable, CLEAR según propuesta |
| c sin referencia con revisiones pendientes | Rechazo por ambigüedad en P2; no aplicar cierre global |
| c→revisión todavía desconocida | Diferir según propuesta; no publicar ScoreUpdate de evento no aplicado |
| Redelivery idéntica de una apertura/cierre | No reabrir ni duplicar; misma identidad distinta carga es conflicto |

No ordenar por timestamp para resolver referencias faltantes. Replay administrativo,
evento diferido, rechazo y publicación deben quedar diferenciados por DEV 1.

## 4. Necesidades concretas de DEV 2

La lectura React ya prioriza var_status: VarEvent conserva el indicador de revisión
en anulación/continuación con IN_PROGRESS. VarReadCompatibility.test.tsx contiene
ocho ejemplos sintéticos de lectura. No prueba el motor ni la validez deportiva.

La escritura estática actual envía MATCH_CONTINUES con referencia null y ofrece
VAR_CHECK ligado al selector de goles. P2 necesitaría una forma **aprobada** de
seleccionar revisión y abrir revisiones generales. No existe en C02 una lista de
revisiones activas. No proponer un motor en el navegador ni inventar un endpoint
de revisiones; acordar primero fuente y alcance (decisión D02).

NotificationPolicy suprime MATCH_CONTINUES. Para GOAL/GOAL_ANNULLED dice «Marcador
confirmado» aun con IN_PROGRESS. Propuesta de texto a aprobar (D09): «Marcador
actualizado: … Continúa una revisión VAR.» en ese estado. No modificar alertas
persistidas ni prometer cierre/confirmación global. Un mensaje ya emitido puede
seguir reflejando el estado de su evento, no el estado actual del partido.

## 5. Versionado, restauración y aprobación

V1 SQL y carga actuales de Match State restringen rules_version a 1. No basta con
cambiar el DEFAULT o añadir 2 a un CHECK. DEV 1 debe definir selección del motor,
replay V1/V2, validación de versión desconocida, activación por partido y rollback.
No migrar un partido a mitad de historial ni modificar payloads outbox ya creados.
Restauración de identidades y de épocas de lectura requiere su propio plan D03.

| Aprobación requerida | Responsable |
| --- | --- |
| P0/P1/P2; generales, simultáneas, anulación directa y cierre explícito | Equipo; DEV 1 concreta reglas y fixtures |
| Referencia desconocida/cerrada, llegada tardía, replay y conflictos | DEV 1 con DEV 2/3 |
| Presentación agregada, textos y alcance del emisor de eventos | DEV 2 con aprobación de semántica del equipo |
| ReviewClosure, ReferenceMode, actor, vigencia y autoridad/leases propios | DEV 3; nunca derivados de CLEAR por DEV 2 |
| Persistencia de versión, migraciones y despliegue/rollback | DEV 1 e infraestructura; consumidores revisados por equipo |

Registrar decisión explícita, versión, escenarios aceptados, partidos de activación,
responsables y referencias de revisión. Esta documentación no firma ni sustituye
esas aprobaciones. Los criterios VAR01/LEGACY01 del
[documento de aceptación](phase6a-acceptance-criteria.md) siguen pendientes de E2E.
