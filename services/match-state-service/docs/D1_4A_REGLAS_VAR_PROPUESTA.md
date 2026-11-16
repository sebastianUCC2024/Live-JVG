# D1.4A — Correlación VAR: diseño y especificación de pruebas

Estado: PROPUESTA, sin aprobación de Gabriel/DEV 2 ni Valeria/DEV 3.
Base inspeccionada: 77c219f76ac3919abf9c039882d0eb11f23c2312,
rama fix/auditoria-integral-fase9. Esta fase solo añade este documento.
No modifica código, formatos, contratos ni reglas; no ejecuta pruebas o infraestructura.
Los cambios pendientes D1.2/D1.3 del productor deben conservarse.

## 1. Evidencia del comportamiento actual (V1)

- MatchState.start crea marcador 0–0 y VAR CLEAR; valida partido y equipos distintos.
- MatchState.apply: GOAL incorpora un gol al historial; no cambia varStatus.
- VAR_CHECK permite reference_event_id null (revisión general). Si existe referencia,
  activeGoal exige un GOAL aceptado del mismo partido, aún no anulado. Pasa a IN_PROGRESS.
- No existe colección de revisiones activas: se conservan los eventos, pero no su ciclo de vida.
- GOAL_ANNULLED exige referencia a un gol activo, comprueba equipo cuando viene informado,
  anula exactamente ese gol y pone CLEAR. No exige VAR_CHECK anterior.
- MATCH_CONTINUES pone CLEAR sin comprobar revisión previa ni su referencia.
  Una referencia no blanca, incluso inexistente, no se interpreta como correlación.
- Mismo event_id y contenido: no-op. Mismo ID con contenido distinto: ID_CONFLICT.
- Marcador calculado desde goles aceptados menos goles anulados; no decrementos ciegos.
- MatchStateService.process crea ScoreUpdate solo para un evento nuevo aceptado,
  con marcador derivado, estado VAR, identidad/referencia originales y hora de procesamiento.
- En modo durable, DurableEventIngress conserva entradas inválidas y difiere
  WAITING_REFERENCE y UNREGISTERED_MATCH. No reaplica automáticamente al llegar el gol:
  requiere replay explícito con revisión esperada y auditoría.
- JdbcMatchStateStore.load usa application_seq, no timestamp/minute, verifica secuencia,
  identidad y revisión, y alimenta MatchState.Rebuilder.accept -> apply(event, false).
  El rebuilder mantiene el mismo comportamiento de negocio que apply ordinario.

Pruebas existentes relevantes (inspeccionadas, no ejecutadas en D1.4A):
MatchStateTest: registersAndAnnulsExactlyOneGoalForEitherTeam,
reviewAndContinuationKeepScoreAndRecordReviewState,
allowsGeneralReviewButRejectsReferenceToNonGoal,
duplicateGoalAndAnnulmentAreNoOpsButConflictingIdsAreRejected,
acceptsOldTimestampsInArrivalOrderAndDoesNotReplayDuplicateReview.
MatchStateReplayTest compara replay y aplicación ordinaria y cubre 20.000 continuaciones.
JdbcHistoryReplayTest verifica cursor, corrupción y versión desconocida con JDBC simulado.
DurableIngressIntegrationTest contiene replay diferido y auditoría con PostgreSQL real:
su existencia no demuestra una ejecución exitosa actual.

## 2. Políticas comparadas

P0 — Global V1: comportamiento actual, cualquier continuación/anulación despeja todo VAR.

P1 — Una sola revisión activa: identidad = event_id del VAR_CHECK, referencia opcional al
gol; rechazar una segunda apertura distinta mientras exista una activa. Continuación
sin referencia cierra la única revisión; referencia informada debe identificarla.
Anulación cierra solo la única revisión asociada al gol; una general no se cierra por
anular otro gol. Se mantiene la corrección de marcador sin revisión previa.
No resuelve por sí sola una continuación tardía sin referencia que llega durante otra revisión.

P2 — Múltiples revisiones, cierre explícito (recomendada para una versión nueva):

1. Cada VAR_CHECK abre una revisión identificada por su event_id; su reference_event_id
   sigue apuntando al gol o es null para revisión general. No abrir dos revisiones
   simultáneas distintas sobre el mismo gol; pueden coexistir revisiones generales
   y de goles distintos. Reabrir un gol no anulado tras un cierre requiere nuevo ID.
2. MATCH_CONTINUES con revisiones pendientes debe identificar el VAR_CHECK en
   reference_event_id. Cierra únicamente esa revisión. Null con pendientes se rechaza
   por ambigüedad incluso con una sola revisión; evita cierres tardíos sin correlación.
3. Sin revisiones pendientes, MATCH_CONTINUES null sigue aceptándose como continuación
   deportiva, sin efecto VAR. Referencia a revisión desconocida: diferida; a una cerrada:
   rechazo semántico para un evento nuevo. La redelivery idéntica sigue siendo no-op.
4. GOAL_ANNULLED conserva reference_event_id = ID del GOAL, nunca ID del VAR_CHECK.
   Corrige el marcador y cierra, si existe, la única revisión asociada a ese gol.
   No cierra revisiones generales ni las de otros goles. Sigue permitiéndose sin
   revisión previa como corrección deportiva; ello no prueba fiabilidad del mercado.
5. IN_PROGRESS si queda al menos una revisión pendiente; CLEAR si no queda ninguna.
   Un gol nuevo nunca cierra revisiones. No se infiere orden causal de timestamps.
6. Misma identidad/contenido no altera estado ni crea una nueva actualización;
   conflictos, cierres ambiguos o inválidos no consumen identidad aceptada ni revisión.

P1/P2 son alternativas propuestas, no hechos implementados. P2 no añade campos,
pero cambia semántica y aceptación de mensajes: requiere aprobación de contrato,
productores y consumidores. No debe desplegarse como parche silencioso de V1.
Si no se aprueba la semántica por tipo de reference_event_id, P2 queda bloqueada;
no se improvisará otro campo ni un nuevo tipo de evento en D1.4B.

## 3. Matriz de escenarios

Notación: g1/g2 goles locales; v1->g1 revisión del gol; vg revisión general;
c->v1 continuación con referencia al VAR_CHECK; a->g1 anulación del gol.
C = CLEAR; I = IN_PROGRESS. Los resultados son posteriores al evento indicado.
Para P1/P2, un rechazo conserva marcador y estado previos y no crea ScoreUpdate.

| Caso y secuencia | Actual P0 | Alternativa P1 | Propuesta P2 | ScoreUpdate | Impacto DEV 2 / DEV 3 | Decisión pendiente |
|---|---|---|---|---|---|---|
| 1. g1 sin revisión | 1–0/C | Igual | Igual | GOAL 1–0/C | UI y alertas de gol; DEV 3 no abre mercado por GOAL | Mantener gol independiente de revisión |
| 2. g1,v1->g1,a->g1 | 0–0/C | Igual | Igual si v1 era la única | Anulación refiere g1; 0–0/C | DEV 2 muestra anulación; DEV 3 conserva su cierre/autoridad propios | Anulación termina revisión deportiva asociada |
| 3. vg general | 0–0/I; continuación null cierra | 0–0/I; null cierra la única | 0–0/I; solo c->vg cierra | Apertura I y cierre C, sin cambio de marcador | DEV 2 necesita origen de cierre; DEV 3 ya identifica revisión por ID | Admitir generales y referencia explícita de cierre |
| 4. g1,v1->g1 | 1–0/I | Igual | Igual | VAR_CHECK con referencia g1 | Campo sigue significando gol al abrir; consumidor no debe confundirlo con revisión | Mantener semántica de apertura |
| 5. g1,g2,v1->g1,v2->g2 | 2–0/I, sin colección activa | Rechaza v2 | 2–0/I, dos revisiones activas | P0/P2 publican v2/I; P1 no | DEV 2 solo recibe indicador agregado; DEV 3 puede conservar dos pendientes | Revisión única o múltiple |
| 6. Caso 5 y a->g1 o c->v1 | Pasa C; a deja 1–0, c deja 2–0 | v2 no se aceptó: cerrar v1 deja C | Queda I por v2; mismo marcador que P0 | P2 publica I en el cierre parcial, sin campos nuevos | UI no debe anunciar fin global; apuestas no deben reabrirse | Cerrar solo revisión identificada |
| 7. c sin revisión previa | 0–0/C | Igual si referencia null | Igual si null; referencia desconocida se difiere | Continuación aceptada produce C; diferida no publica | Notificaciones actuales omiten MATCH_CONTINUES; DEV 3 no abre por continuación | Mantener continuación deportiva; clasificar referencia desconocida |
| 8. g1,a->g1 sin VAR_CHECK | 0–0/C | Igual | Igual; no cierra generales pendientes | Anulación válida publica marcador corregido | DEV 3 marca incertidumbre si no puede correlacionar; no prometer OPEN | Admitir corrección directa o exigir revisión (alternativa más estricta) |
| 9. Redelivery g1/v1/a/c | No-op idéntico; conflicto si cambia contenido | Igual | Igual; no reabrir revisión cerrada por redelivery | Sin outbox nueva para duplicado; una pendiente sí puede reenviarse | DEV 2/3 deduplican entrega al menos una vez | Mantener identidad por partido y contenido completo |
| 10. a->g1 antes de g1; cierre antes de apertura; timestamps viejos | Referencia a gol se difiere; continuación se acepta y no espera apertura; orden de llegada | Gol faltante diferido; cierre null puede cerrar revisión equivocada si llega tarde | Gol/revisión faltante diferidos; null con pendientes rechazado; tiempo viejo no reordena | Diferido no publica; replay aceptado publica a la revisión vigente | DEV 3 consume Kafka bruto y puede aplicar política distinta; no hereda cuarentena DEV 1 | Acordar orden, replay y revisiones esperadas |
| 11. Reinicio/reconstrucción persistida | Reproduce reglas V1 por application_seq | Solo historiales creados bajo P1; V1 requiere motor anterior | Solo nuevos historiales V2 bajo P2; V1 conserva motor anterior | Cargar historial no crea outbox; pendientes conservan payload/destino | Evitar cambiar silenciosamente UI/mercado o republicar pasado | Versionado, alcance de activación y rollback |

Variantes obligatorias: g1,vg,a->g1 debe dejar I en P1/P2; dos VAR_CHECK distintos
sobre g1 se rechazan en P2; dos generales se aceptan en P2 y cada una requiere su cierre.
Referencia de cierre a GOAL en vez de VAR_CHECK es inválida en P2, sin alterar g1.
La secuencia v1,c->v1,v2,c_tardío->v1 no puede cerrar v2.

## 4. Defectos y límites confirmados por inspección

1. No puede representar qué revisiones siguen abiertas. Tras dos aperturas y un
   cierre parcial, CLEAR pierde la segunda revisión. Falta prueba dedicada de solapamiento.
2. MATCH_CONTINUES ignora identidad/existencia de su referencia: una continuación
   tardía o referida a otra revisión puede despejar el VAR actual.
3. Una anulación de g1 despeja una revisión general o sobre g2 no relacionada.
4. Replay y procesamiento comparten apply: cambiar sus reglas sin versionado cambia
   la interpretación del historial existente. rules_version aún no selecciona motores.

Permitir revisión general, continuación sin revisión o anulación directa es
comportamiento intencional en las pruebas actuales; no se etiqueta como defecto
sin aprobación de una política distinta. Procesar por llegada también es política
existente, no un fallo de ordenamiento que deba arreglarse ordenando por timestamp.

## 5. ScoreUpdate y coordinación

No se propone añadir estados ni campos. ScoreUpdate seguirá mostrando estado agregado;
no permite reconstruir una lista autoritativa de revisiones en el frontend por sí solo.
event_id/event_type identifican el evento aplicado, no siempre la revisión que cierra.
Con P2, reference_event_id depende del tipo: gol para apertura/anulación; revisión
para continuación dirigida. Gabriel debe revisar también productores de eventos,
fixtures y recuperación de snapshots, no solo la compatibilidad JSON.

Gabriel/DEV 2 debe aprobar: I en anulación parcial, semántica de cierre dirigido,
presentación de revisiones generales, continuidad sin revisión y mensajes de alertas.
NotificationPolicy actual dice «Marcador confirmado» en anulación aun si otra revisión
continúa; debe acordarse su redacción. Actualmente no emite alerta MATCH_CONTINUES.
No modificar ese servicio en esta fase ni asumir que una alerta de cierre existe.

Valeria/DEV 3 debe aprobar: apertura/correlación compartidas; una revisión por gol;
tratamiento de anulación directa y eventos fuera de orden; puente operativo entre
continuación deportiva y ReviewClosure(commandId, reviewId, continuationEventId,
expectedVersion, actor, reason). Ese comando es interno, no HTTP/Kafka operativo.
P2 NO implementa ni reemplaza ese puente: MarketState.apply no cierra revisiones
automáticamente, incluso con una continuación dirigida. La autoridad y leases
del worker siguen siendo responsabilidad de DEV 3. CLEAR nunca autoriza apuestas.

Ambos deben aprobar P1 o P2, reglas de cierres redundantes, versión de política,
activación por partido y compatibilidad con fixtures/clientes actuales que mandan
continuación null. No se han enviado mensajes ni registrado aprobación de nadie.

## 6. Historiales y rules_version

V1 SQL fija CHECK(rules_version = 1), y JdbcMatchStateStore.load rechaza valores
distintos de 1. MatchState/LoadedMatch no transportan la versión como política.
Por tanto, actualizar la columna o DEFAULT sin adaptar el motor NO es suficiente.

Propuesta: conservar íntegramente motor y fixtures V1; nuevos partidos optan a V2
tras aprobación. Elegir política al crear/cargar el agregado y mantenerla durante
procesamiento y replay administrativo. V1 sigue aceptando sus continuaciones null
y anulaciones sin apertura; no se reinterpreta su pasado mediante reglas V2.
No convertir partidos existentes automáticamente ni a mitad de historial.

Una migración futura aditiva, separada y revisada, debería permitir versiones 1/2
y registrar la política de nuevos partidos explícitamente; no editar V1 ya aplicada.
Hasta autorizarla, trabajar V2 con fixtures en memoria y JDBC simulado únicamente.
Mantener regla de fail-closed para versiones desconocidas y conflictos de catálogo.
No cambiar la versión de los partidos existentes al actualizar la configuración.

No regenerar ScoreUpdate ni reserializar la outbox histórica, publicada o pendiente.
Una nueva versión no debe crear diferencias entre el estado reconstruido y esos
payloads inmutables. Un rollback a un binario V1 debe rechazar partidos V2;
su despliegue requiere una estrategia compatible, no bajar versiones en SQL.

## 7. Especificación de pruebas para D1.4B (no creadas ni ejecutadas)

| Grupo / ubicación futura | Casos y aserciones |
|---|---|
| Caracterización V1: MatchStateTest/MatchStateReplayTest | Congelar los once escenarios actuales, incluso solapamiento global, cierre sin correlación y anulación directa; no borrar pruebas que dejan de encajar con V2 |
| Dominio V2: nueva MatchStateVarCorrelationTest | Apertura general/de gol; dos goles revisados; cierre parcial conserva I; último cierre produce C; anulación no cierra general/otro gol; segunda apertura del mismo gol rechazada; cierre tardío no afecta revisión nueva |
| Identidad y rechazo | Duplicados tras cierre no reabren; mismo ID distinto contenido falla; referencia a no revisión falla; referencia ausente/pendiente diferenciada; colecciones inmutables; estado e historial sin cambios al rechazo |
| Aplicación: MatchStateServiceTest | Clock fijo, ScoreUpdate completo en cierre parcial/final, CAS y outbox únicos; rechazo no llama compareAndSet; duplicado no crea salida; revisión esperada obsoleta falla |
| Replay: MatchStateReplayTest | V1 y V2 cada una reproducen exactamente su aplicación ordinaria; conjuntos de revisiones, marcador, historial e IDs iguales; rechazos iguales; no ordenar por timestamp |
| Persistencia simulada: JdbcHistoryReplayTest | Selección de motor por rules_version; V1 preservado; V2 conserva pendientes tras carga; versión desconocida/corrupción rechazada; ausencia de escrituras al cargar; payload outbox original |
| Ingress: DurableReferencePolicyTest y pruebas locales nuevas | Cierre dirigido antes de apertura queda diferido; reglas ambiguas rechazadas; replay tras aprobación produce una sola salida; errores de almacenamiento no se convierten en rechazos semánticos |
| Componentes: MessagingPipelineTest | Secuencia completa con dos revisiones, duplicados y fallo de publicación usando dobles; clave/ruta y JSON ScoreUpdate sin nuevos campos |

Integración PostgreSQL/brokers y migración real son posteriores y requieren entorno
aislado autorizado. Dobles JDBC no demuestran restricciones/transacciones reales.

## 8. Plan D1.4B y archivos potenciales

1. Registrar decisiones explícitas de Gabriel y Valeria sobre P2 y excepciones.
   Criterio: semántica por evento, cierre, orden y versión sin ambigüedades.
2. Añadir caracterización V1 en tests de dominio/replay y preparar fixtures locales.
   Criterio: comportamiento histórico congelado, incluso casos hoy problemáticos.
3. Introducir selección explícita de política y revisiones inmutables en MatchState
   (posibles modelos nuevos VarReview/VarPolicy), adaptar LoadedMatch, creación
   y configuración en MessagingConfiguration/DurableStorageConfiguration.
   Criterio: V1 intacta y V2 opt-in; memoria y JDBC seleccionan la misma política.
4. Adaptar JdbcMatchStateStore.load/compareAndSet/register, MatchStateService y
   DurableEventIngress.replay/rejection; InvalidMatchEventException para razones
   de correlación; preservar clasificación diferible frente a permanente.
   Criterio: aplicación y reconstrucción usan la versión del partido, con CAS,
   auditoría e identidad conservadas. No modificar formato MatchEvent/ScoreUpdate.
5. Preparar, tras aprobación separada, migración siguiente para rules_version;
   revisar selección de versión y compatibilidad de despliegue. No ejecutarla aquí.
6. Ejecutar tests locales autorizados de dominio, aplicación, replay y componentes,
   después coordinar validación DEV 2/3 y finalmente infraestructura aislada.

No deben cambiarse silenciosamente contratos compartidos ni código de compañeros.
Si sus consumidores necesitan ajustes, se entregarán requisitos para su trabajo.
D1.4A termina antes del paso 1 de implementación y no autoriza D1.4B.
