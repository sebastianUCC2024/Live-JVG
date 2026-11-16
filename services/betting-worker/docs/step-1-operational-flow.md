# Paso 1 — Flujo operativo interno de apuestas simuladas

Fecha: 2026-11-16. Rama `integration/Valeria-mvp-dev`, HEAD inicial `77c219f`. Los dos documentos sin seguimiento de Fase 7 se conservaron. El avance respecto de `3e89b86` corresponde a frontend/alertas del equipo; no se modificaron esos archivos.

## Implementación existente y alcance completado

Ya existían listener Rabbit opt-in, decoder del formato provisional, validación de identidades, inbox idempotente, detección de conflictos command_id/operation_id, almacenamiento JDBC, historial/evidencia, cinco estados, consulta REST, retries y autorización atómica opt-in. No se reescribieron ni se habilitaron esos componentes.

Se añadió `WorkerService.submit(SimulatedCommand)`: entrada de aplicación interna que recibe un objeto de dominio ya validado. No necesita decodificar un comando externo ni abrir endpoints. Reutiliza exactamente `WorkerStore.handle`, su TransactionTemplate, sus locks, su política y su puerto de autorización. `DomainCommandPreparation` genera una imagen canónica determinista y evidencia con source `internal/domain`, sin inventar message_id o confirmación Rabbit. El JSON interno reutiliza las seis propiedades de la propuesta local existente; no constituye aprobación de ese contrato.

Identidades: el caller proporciona commandId y operationId estables; no regenerarlas al reintentar. Misma identidad y contenido → DUPLICATE, contenido distinto → IDENTITY_CONFLICT, conservando la operación original. La canonicalización coincide con el decoder provisional para el mismo JSON de timestamp; no se normalizaron retrospectivamente otras representaciones ISO de recibos existentes. Una representación textual distinta puede seguir siendo conflicto conforme a la política previa.

También se corrigió el contador durable de entregas duplicadas: ahora exige exactamente una fila actualizada. Antes podía devolver DUPLICATE si ese UPDATE no persistía ninguna fila. Ahora falla y revierte, evitando confirmar una disposición sin su trazabilidad.

Uso interno, sobre el bean existente y una base previamente migrada y autorizada:

```java
var command = new SimulatedCommand(commandId, operationId, matchId, simulatedUserId, timestamp);
var outcome = workerService.submit(command);
var operation = workerService.find(operationId);
```

`PROCESSED` significa decisión persistida, no necesariamente ACCEPTED. Consultar state/reason. No se creó productor Rabbit, POST HTTP ni tarea automática. `GET /api/betting/operations/{operationId}` permanece como consulta existente, sin exponer simulated_user_id.

## Seguridad, transiciones y recuperación

- Se mantienen consumo/aceptación false, autorización disabled y Flyway false. Los comandos de dominio tampoco evaden esos controles.
- UNKNOWN/SUSPENDED, OPEN obsoleto, evidencia de otro partido o insuficiente no producen aceptación. Una lectura OPEN elegible sin sesión/grant atómico termina BLOCKED.
- Persistencia caída o fallo del lock/grant autoritativo propagan excepción: no se devuelve éxito ni se persiste un falso BLOCKED si PostgreSQL no permite confirmar. Se revierte la transacción y el caller reintenta el mismo comando. El listener conserva su mecanismo de retry/stop/requeue.
- RECEIVED e historial inicial y terminal se guardan dentro de la misma transacción; la consulta no expone un RECEIVED parcialmente confirmado. No se añadieron transiciones desde estados terminales.
- Un proceso nuevo consulta PostgreSQL y deduplica mediante inbox; no depende de cache en memoria. El test con mocks solo verifica las llamadas; recuperación durable requiere la prueba PostgreSQL preparada.
- `postgres-atomic` sigue exigiendo DB común control/worker, permisos restringidos, epoch/lease válidos, lock de origen y autorización/aceptación en la misma conexión/transacción. No se cambió SQL, permisos ni leases. Atomicidad real sigue NO certificada.

## Cobertura de comportamiento

| Caso solicitado | Cobertura local / preparación real |
|---|---|
| Comando válido | Nueva entrada de dominio → store; imagen compatible con decoder local |
| Inválido | Null/identidad vacía antes de almacenamiento; decoder y cuarentena existentes |
| Duplicado idéntico | Inbox existente; nueva prueba PostgreSQL ampliada desde submit y tras nueva instancia |
| Duplicado conflictivo | Canonical diferente; auditoría e integridad de operación original |
| UNKNOWN / SUSPENDED | Nueva prueba de flujo dominio → JDBC → BLOCKED |
| OPEN sin permiso | Pruebas existentes OPEN solo lectura, lease vieja y ausencia de grant |
| Persistencia | Rollback existente; nueva regresión UPDATE del duplicado con cero filas |
| Reintento | Nuevo test conserva exactamente el comando preparado tras excepción; retry Rabbit existente |
| Reinicio y recuperación | Nueva instancia de store/service en prueba PostgreSQL preparada |
| Consulta | WorkerService/find, controller y lectura durable existentes |
| Estados | RECEIVED a cada terminal; nuevos flujos REJECTED por timestamp y FAILED por scope |
| Autorización / fail-closed | Tests de grant denegado, caída fuente, transacción/DB/epoch y guard existentes |

Se amplió `PreparedPostgresIntegrationTest` con submit/consulta, duplicados y conflicto tras nueva instancia, ausencia de doble transición, rollback de inbox ante autoridad caída y reintento posterior. Mantiene gate `DEV3_RUN_POSTGRES_TESTS`; no se ejecutó. Los once casos de concurrencia PostgreSQL siguen omitidos. No se certifica SQL ni reinicio físico usando mocks o instancias Java nuevas.

## Validación de este paso

Maven `test` y `package` por POM individual, Java 17 explícito, sin infraestructura externa. Resultados y comandos exactos: `evidence/step-1-local-results.json`. Package repite pruebas: no se duplican conteos.

| Módulo | Exitosas | Omitidas | Fallos | Errores |
|---|---:|---:|---:|---:|
| historical-archiver | 68 | 1 | 0 | 0 |
| betting-suspension-service | 93 | 1 | 0 | 0 |
| betting-worker | 94 | 12 | 0 | 0 |
| Total | **255** | **14** | **0** | **0** |

Ocho pruebas locales adicionales. La prueba PostgreSQL existente se amplió sin contabilizarla como ejecutada. Sin migraciones ni contenedores, sin cambios fuera de betting-worker en este paso, sin commits/push. Fuentes nuevas/modificadas: WorkerService, DomainCommandPreparation, JdbcWorkerStore; pruebas: WorkerServiceTest, JdbcWorkerStoreTest, PreparedPostgresIntegrationTest; documentación: este informe y JSON de resultados. Los informes previos de Fase 7 permanecen intactos.

## Condiciones pendientes y Paso 2

Se puede avanzar al Paso 2 dentro del alcance que se autorice: el flujo interno funciona localmente y conserva su cierre seguro. Esto no aprueba activar apuestas ni certifica integración externa.

Contratos requeridos antes de conectar Rabbit: comando/versionado/identidades, exchange/cola/routing y productor; semántica de cierre VAR y liveness de apertura. PostgreSQL real requerido: migraciones/roles/owners en DB dedicada, pruebas de persistencia/rollback/reentrega y concurrencia atómica con dos conexiones. Se necesita también verificar/emitar leases contra una fuente al día. No se habilitará aceptación con solo OPEN ni por pasar esta suite local.
