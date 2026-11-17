# Contratos: matriz de compatibilidad y aprobación pendiente

Complementa REVIEW.md; no publica contratos compartidos.

| Elemento | DEV 1 real | Gabriel / DEV 2 real | Valeria / DEV 3 real | Decisión requerida |
|---|---|---|---|---|
| MatchEvent | Producer y MatchEventMessage: siete campos snake_case, minuto 0..130, Instant | Gateway envía al Producer; no es propietario Kafka | JsonEventDecoder de Archiver/Suspension rechaza campos extra, JSON duplicado y key distinta de match_id; opcionales ausentes equivalen a null | Ambos: evolución sin añadir campos unilateralmente; identidad global o por partido |
| ScoreUpdate | Ocho campos; marcador calculado y updated_at de procesamiento | Dashboard ScoreUpdateMessageDecoder y Notification ScoreUpdateCodec aceptan referencia ausente; ambos exigen referencia no nula al anular | No es evidencia autoritativa para Worker | Gabriel: aprobar formato, orden, recuperación y referencia en anulación |
| LiveAlert | DEV 1 no lo produce | AlertController.AlertResponse es REST local contract_version=1.0; content locale/title/message; no hay publicación Rabbit de LiveAlert | No hay consumidor operativo de este contrato | Gabriel: aprobar DTO REST o definir transporte aparte; Valeria: confirmar frontera de responsabilidad |
| BettingCommand | No productor operativo; harness solo simulado | Dashboard no recibe ownership por esta propuesta | JsonCommandDecoder privado exige schema_version=1 y cinco campos de texto/tiempo, rechaza extras; SimulatedCommand sin importes | Valeria: topología/ownership/reintentos; Gabriel: confirmar integración deseada sin asignación implícita |
| VAR_CHECK.event_id | V1 lo deduplica; PreparedVarCorrelation lo usa como reviewId solo en fixtures | UI solo recibe agregado | MarketState abre PendingReview con ese ID | Ambos: identidad de revisión, aperturas generales y múltiples sobre mismo gol |
| MATCH_CONTINUES.reference_event_id | V1 no correlaciona y despeja globalmente; candidato local cierra revisión explícita | Producer actual permite referencias; consumidores no reconstruyen lista autoritativa | MarketState.apply NO cierra; close requiere ReviewClosure, versión y evidencia, con ReferenceMode V1_GOAL/V2_REVIEW | Ambos: aprobar referencia y redundancia; Valeria: puente externo y cierre de bloqueos GOAL |
| GOAL_ANNULLED | Referencia al GOAL activo, corrige marcador y despeja V1; candidato V2 lo rechaza explícitamente | Notificación dice marcador confirmado aunque otra revisión pueda seguir | Suspende; no cierra automáticamente; marca incertidumbre si no correlaciona | Ambos: dos identidades inequívocas gol/revisión y comportamiento sin revisión; Gabriel: texto de alerta |
| V1/V2 | Wire sin discriminador; SQL y loader solo rules_version=1 | Decoders estrictos rechazarán un nuevo campo desconocido | Decoders Kafka estrictos también | Ambos: discriminador, activación por partido, migración aditiva y rollback |
| Históricos | application_seq manda; tiempos no reordenan; outbox conserva payload/destino | Inbox deduplica, snapshots propios | Archiver V2 UNIQUE(match_id,event_id); Suspension deduplica por partido | Ambos: mantener replay V1 sin reinterpretación |

Propuestas localizadas en contracts/ bajo este directorio. Se corrigió required para
opcionales ausentes en MatchEvent/ScoreUpdate y se añadió referencia obligatoria
condicional para anulación en ScoreUpdate. Veinte fixtures cubren presencia/ausencia,
campos extra, tiempos inválidos y referencia inválida. Estos schemas son una propuesta
de frontera común: no sustituyen la validación contextual de equipos, referencias,
orden, custodia ni identidad. LiveAlert conserva la forma REST observada.

Discrepancias adicionales: MatchState histórico puede tolerar algunos blancos opcionales
que la propuesta rechaza; timestamps JSON pueden admitir variantes distintas de Instant.parse.
Los schemas no detectan claves JSON duplicadas. El codec Notification limita 65536 bytes.
Archiver V2 ya alinea identidad por partido; requiere migraciones V1/V2 para arrancar.
No afirmar compatibilidad total solo por validación de fixtures.

PreparedVarCorrelation es una proyección candidata sin beans, listeners, persistencia ni
cálculo de score. Prueba aperturas/cierres dirigidos y replay; no es un motor V2 completo.
V2 PostgreSQL no existe: loader rechaza rules_version=2. Nueva prueba PostgreSQL V1
preparada para recuperación de revisiones superpuestas; no ejecutada externamente.

Aprobación solicitada: Gabriel y Valeria deben registrar decisión explícita por cada
fila semántica, especialmente GOAL_ANNULLED, GOAL holds y discriminación V1/V2.
Sin aprobación recibida. Ningún archivo contracts/events se modifica.

## Decisiones concretas tras PR #26: todas pendientes de aprobación conjunta

| Decisión | Propuesta para Gabriel y Valeria |
|---|---|
| Identidad | VAR_CHECK.event_id identifica revisión dentro de match_id. |
| Continuación V2 | reference_event_id apunta a VAR_CHECK; cierre individual, parcial IN_PROGRESS y total CLEAR. Acordar cierre redundante y referencia desconocida. |
| Anulación V2 | Mantener referencia al GOAL y representar revisión por identidad explícita adicional o cierre separado; elegir UNA representación antes del schema. No inferirla por timestamp. |
| GOAL hold | CLEAR deportivo no cierra hold GOAL ni concede aceptación; Valeria define comando/evidencia independiente y Gabriel confirma presentación. |
| Discriminación | Versión explícita a acordar y política inmutable al crear partido; ausencia conserva V1. Decoders cerrados requieren despliegue coordinado. |
| Replay | Motor según versión persistida; V1 conserva reglas, historial y outbox originales. Replay no renueva leases ni permisos históricos. |
| Orden/identidad | application_seq manda en DEV 1; Kafka key=match_id. Duplicado idéntico no-op; mismo ID con otro contenido es conflicto durable. |
| Custodia | Entrada inválida no muta agregado ni publica; evidencia durable antes de ACK/offset. Referencias ausentes requieren replay auditado, sin reordenar por hora. |

Gabriel debe aprobar envelope/canales, fixtures, snapshots, UI y textos de alerta.
Valeria debe aprobar correlación con ReviewClosure, GOAL holds, disposición durable,
versionado de consumidores y operación de autoridad. Registrar voto explícito por fila.
La propuesta DEV 3 final-contract-proposal.md coincide en identidad y decoders cerrados,
pero no constituye aprobación de Gabriel ni autorización de activación. PR #26 prepara
readiness/probes, no emisor operativo de leases. PreparedVarCorrelation sigue aislado.
