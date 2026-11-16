# Fase 5D — Recuperación de publicación y preparación de lectura VAR V2

Base verificada: rama `feature/Gabo-react-integration`, HEAD
`c1df3f7869680d8ffd07ca78447cfa0fb1c8ae7f` (merge del PR #18).
La modificación inicial de Fase 5C se conserva: EventPreparationException sigue
incluida en la compilación dinámica de Dev1LiveWireCompatibilityTest. Sus tres
casos y aserciones permanecen activos. No hubo operaciones de sincronización Git.

## Contrato confirmado del productor

Se inspeccionaron en lectura publication-recovery.md, MatchSimulationController,
MatchSimulationService, EventPublicationException y EventPreparationException.

- POST `/api/matches/{matchId}/events` mantiene la solicitud existente.
- 202: MatchEvent original sin envelope; la confirmación Kafka no implica que
  Match State o dashboard ya lo hayan procesado.
- 500 reconocido: `EVENT_PREPARATION_FAILED`, preparación anterior al envío.
- 503 reconocido: `PUBLICATION_UNCONFIRMED`, resultado incierto del envío.
- El envelope de error puede conservar el evento original completo. No hay API
  nueva de recuperación ni almacenamiento nuevo en el productor.
- Si se pierde la respuesta, el envelope no permite recuperar nada: es necesario
  haber conservado la identidad y el payload antes del POST.

## Cambio de gateway y propagación MVC

ProducerGateway sigue conservando los estados HTTP del productor. Para las parejas
500/EVENT_PREPARATION_FAILED y 503/PUBLICATION_UNCONFIRMED construye un error local
PublicationFailure. ApiExceptionHandler devuelve su body mediante ResponseEntity,
con status original y Cache-Control no-store. Las pruebas atraviesan el controlador
real y este advice, usando transporte HTTP y ScoreProjection simulados.

El body contiene solo `code`, un `error` generado por dashboard y `event` cuando
supera la validación. No se reenvía el texto arbitrario de la excepción upstream,
stack traces ni campos adicionales. El parser rechaza claves JSON duplicadas,
tokens residuales y envelopes mayores de 64 KiB. Un código desconocido, JSON
ilegible o pareja código/status incorrecta mantiene el tratamiento genérico previo.

El evento recuperable requiere los siete campos reales, tipos y valores válidos:
event_id, match_id, event_type, team_id, minute, timestamp, reference_event_id.
Se comprueban partido, tipo, equipo, minuto y referencia contra la solicitud;
ID y timestamp se contrastan cuando el cliente los proporcionó. El timestamp se
compara como Instant sin perder nanosegundos; su representación original recibida
se conserva en el DTO. Se eliminan campos adicionales del evento.

Si la identidad o contenido difieren, se omite event y se devuelve un mensaje
controlado de incompatibilidad. Si falta event no se fabrica. Si el cliente no
envió ID ni timestamp, un evento completo válido permite recuperar los valores
que generó el productor. Esto no crea un registro del lado servidor.

Ejemplos ilustrativos con identidad ya existente; no son evidencia de envío real:

```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json
Cache-Control: no-store

{
  "code": "EVENT_PREPARATION_FAILED",
  "error": "No se pudo preparar el evento. Conserva la operación original antes de reintentar.",
  "event": {
    "event_id": "original-id", "match_id": "m", "event_type": "GOAL",
    "team_id": "home", "minute": 10,
    "timestamp": "2026-11-16T20:00:00.123456789Z", "reference_event_id": null
  }
}
```

Para el mismo evento, un error 503 usa `code: PUBLICATION_UNCONFIRMED` y el texto
`Publicación no confirmada. Conserva el mismo evento sin cambiar su identidad.`.
No es éxito ni rechazo definitivo de Kafka. Timeout/desconexión siguen dando un
resultado incierto sin evento recuperado inventado. 400 y los demás rechazos
mantienen su semántica previa; 409 no adquiere aquí una política contractual nueva.

## Cliente estático

PendingOperations conserva el body serializado antes del POST, ID y timestamp,
Web Locks y bloqueo de doble envío. No adopta el evento de un error para reemplazar
su body. Usa mensajes locales controlados para los códigos conocidos; si recibe
un evento incompatible lo señala y conserva la operación original. La comparación
del timestamp en este cliente es conservadora por representación textual; nunca
reescribe el timestamp guardado para normalizarlo.

500, 503 y 409 mantienen la operación pendiente. Un fallo de preparación del intento
actual no borra incertidumbre de un intento anterior. Ni siquiera un envelope
recuperable convierte una operación en aceptada. 202 requiere el ID esperado.
Timeout, pérdida de cuerpo y respuesta ilegible conservan la operación.

El reenvío es manual: antes consulta GET `/api/matches/{id}/events/{eventId}`.
`processed: true` confirma llegada al inbox del dashboard y evita otro POST;
`false` no demuestra rechazo Kafka. Consulta fallida no autoriza reenviar.
La recarga recupera el estado local sin generar otro ID. Olvidar una operación
sigue requiriendo decisión explícita y no cancela lo que ya pudo publicarse.

Los tests de app ejecutan los scripts y handlers reales con DOM/HTTP simulados:
verifican el texto de feedback, el ID visible, los controles y la conservación
del body ante 500/503, sin mostrar el mensaje privado del transporte.

## VAR V2 / P2: lectura únicamente, sin activar reglas

La propuesta D1_4A_REGLAS_VAR_PROPUESTA.md sigue pendiente de aprobación conjunta.
No se implementó motor, colección de revisiones, cierre dirigido ni cambios de
contrato/persistencia. No se reinterpretaron historiales V1.

| Área DEV 2 | Lectura compatible | Adaptación o acuerdo pendiente |
| --- | --- | --- |
| ScoreUpdate Java/dashboard/notification | Los ocho campos y enums existentes permiten IN_PROGRESS en anulación y continuación; referencias son IDs opacos | No reconstruir revisiones activas ni inferir su número |
| REST/STOMP | Conservan snapshot autoritativo, stream_id, revision y referencia sin reinterpretación | Validar secuencias V2 y replay cuando DEV 1 las implemente y autorice |
| Validadores React | Aceptan VAR_CHECK general/null, continuación dirigida a revisión y anulación parcial IN_PROGRESS | No inventar campos para la colección de revisiones |
| VarEvent | IN_PROGRESS tiene prioridad sobre el tipo de evento: un cierre parcial sigue mostrando revisión en curso | UX de detalle puede evolucionar tras acuerdo; el banner no declara cierre global |
| Historial y alertas | Conservan referencias y tipos de evento | Referencia de MATCH_CONTINUES pasa a significar revisión; no describirla siempre como gol |
| Simulador estático | No altera reglas de lectura y conserva identidad | Hoy MATCH_CONTINUES envía referencia null y VAR_CHECK depende del selector de goles; no puede emitir todos los escenarios P2 sin una fuente autorizada de revisiones y cambios aprobados |
| NotificationPolicy | Consume los campos; MATCH_CONTINUES continúa SUPPRESSED | No generar una alerta nueva de cierre sin aprobación |

Texto potencialmente incorrecto: NotificationPolicy emite «Marcador confirmado»
para GOAL/GOAL_ANNULLED incluso con IN_PROGRESS. Propuesta para revisión del equipo:
usar «Marcador actualizado: … Continúa una revisión VAR.» cuando IN_PROGRESS y
mantener el indicador agregado recibido. No se cambió NotificationPolicy ni el
contenido persistido de alertas. El frontend muestra el contenido de la API.

Ocho pruebas sintéticas de lectura en VarReadCompatibility.test.tsx comprueban
formatos y presentación del estado agregado, no la validez deportiva de P2.
No constituyen aprobación ni implementación de la propuesta. CLEAR nunca autoriza
apuestas ni sustituye los comandos/leases del servicio de DEV 3.

## Validación local

JDK Oracle 17.0.12 existente y Maven 3.9.16 almacenado; JAVA_HOME/PATH solo en las
sesiones usadas. Sin instalaciones ni descargas. Maven se ejecutó offline con
cachés existentes, perfiles externos inactivos y consumidores/Flyway desactivados.
Se preservaron reportes anteriores antes de ejecutar. Los primeros logs incluyen
un intento con argumento CLI incorrecto; la invocación se corrigió y todos los
resultados finales siguientes se verificaron por exit code y reportes.

| Comprobación | Resultado final |
| --- | --- |
| ProducerGatewayIdentity/Recovery/Timeout | 31 pruebas aprobadas en la suite completa (28 también verificadas inicialmente por selección) |
| dashboard-backend verify | 150 aprobadas, 0 fallos/errores/omisiones, BUILD SUCCESS |
| Dev1LiveWireCompatibilityTest | 3 aprobadas, con la línea de Fase 5C preservada |
| notification-backend verify | 103 aprobadas, BUILD SUCCESS; sin cambios de código |
| Node dashboard | 34 aprobadas (23 anteriores y 11 nuevas) |
| React Vitest | 143 aprobadas, 15 archivos (135 anteriores y 8 de lectura) |
| TypeScript/Vite build | Correcto |
| Playwright Chrome | 14 aprobadas con fixtures locales, escritorio y móvil; salida 0 |
| git diff --check | Sin errores |

Evidencias ignoradas por Git: `dashboard-backend/target/phase5d/`,
`notification-backend/target/phase5d/` y `dashboard-frontend/test-results/phase5d/`.
La prueba Maven inicial del gateway pasó 28 casos; las tres nuevas guardas de
código/status y tamaño también pasaron dentro de verify. Playwright administra un
Vite temporal, intercepta REST/STOMP y bloquea peticiones inesperadas. No ejecuta
productor, brokers ni bases. Estos resultados no certifican la cadena E2E real.

## PR y dependencias pendientes

Proponer PR de esta rama hacia dev, sin crearlo ni publicar automáticamente:
`fix(dashboard): preserve safe producer publication recovery responses`.
Incluir ProducerGateway, ApiExceptionHandler, operations.js, sus tres archivos
de tests (ProducerGatewayRecoveryTest, operations.test.cjs, app.test.cjs), la línea
pendiente de Dev1LiveWireCompatibilityTest, el test React de lectura, este documento
y el enlace README. No incluir target, dist, test-results ni archivos de DEV 1/3.

DEV 1 debe coordinar escenarios reales de publicación incierta y duplicados,
contratos, identidad tras restauración/replay y aprobación/versionado de P2.
DEV 2 debe validar el proxy, sus emisores y consumidores con el harness aprobado.
DEV 3/infraestructura debe coordinar recursos aislados, credenciales privadas,
puertos, permisos, CORS y acciones de migración/reinicio. E2E requiere autorización
conjunta posterior. Nada de ello se ejecutó aquí.

Reenviar el mismo evento puede generar registros Kafka duplicados. La idempotencia
de negocio depende del flujo consumidor. localStorage no es almacenamiento durable
del servidor ni portable entre orígenes; una respuesta perdida no se recupera por
el envelope del PR #18. No se promete entrega exactamente una vez.
