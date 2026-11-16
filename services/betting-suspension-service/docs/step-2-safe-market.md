# Paso 2 — Suspensión y cierre interno seguro

Rama `integration/Valeria-mvp-dev`, HEAD `77c219f`, 2026-11-16. Se conservaron los cambios del Paso 1 y los informes de Fase 7. Contratos compartidos: los cuatro JSON de `contracts/events` siguen vacíos. No se hizo fetch/pull ni se presume aprobación de otras ramas.

## Comportamiento completado

Ya existían UNKNOWN/SUSPENDED/OPEN, ledger por `(match_id,event_id)`, recibos Kafka, cuarentena, cierres internos versionados/idempotentes, outbox, permisos y locks de autorización SQL. Defectos comprobados: GOAL conservaba OPEN; cierre sin vigencia temporal explícita; continuación antigua reutilizable tras eventos nuevos; escrituras de auditoría/outbox sin comprobar número de filas.

- GOAL crea un **bloqueo interno** en `pending_review`, identificado por su event_id y referenciado a sí mismo, y pasa a SUSPENDED. No se fabrica un evento VAR_CHECK. Se reutilizan las tablas existentes, sin migraciones nuevas.
- VAR_CHECK añade otro bloqueo independiente. No consume ni cierra automáticamente el bloqueo del GOAL, incluso si comparten referencia. Esta es una política interna conservadora: no una interpretación aprobada del protocolo VAR. El caller interno debe cerrar explícitamente cada bloqueo con evidencia; hasta el último cierre el estado sigue SUSPENDED.
- GOAL_ANNULLED mantiene SUSPENDED. Referencia no reconocida marca inconsistencia e impide reapertura. MATCH_CONTINUES solo registra un hecho, sin cerrar bloqueos ni autorizar apuestas.
- `ReviewClosure` interno añade `evidenceIssuedAt` y `evidenceExpiresAt` (Instant obligatorios, intervalo positivo). Se exige issued <= ahora < expires. No se asigna un TTL de negocio implícito: la autoridad interna futura debe aprobar y acotar la vigencia. No hay endpoint, listener, scheduler ni emisor automático de estas evidencias.
- Cierre exige revisión/bloqueo existente, partido y versión exactos, mercado consistente y continuación persistida posterior al bloqueo. La continuación debe ser el último evento observado; si incluye reference_event_id debe coincidir con el bloqueo. Se rechazan timestamps de continuación futuros o anteriores al evento bloqueante y versiones de ledger imposibles.
- Actor/motivo, correlación y ventana de evidencia quedan en `closure_command.canonical_request`, con timestamps ISO. Es auditoría interna, no un token autenticado ni prueba de catch-up Kafka.
- Inserciones de evento, bloqueo, cierre, historial y outbox deben escribir exactamente una fila; fallo provoca rollback. Estado/evidencia/outbox siguen dentro de la misma transacción y lock de mercado. Outbox se crea solo al cambiar estado; los eventos y cierres sin cambio de estado conservan sus respectivos ledgers.

## Transiciones

| Entrada | Resultado seguro |
|---|---|
| Sin datos | UNKNOWN, no autoriza |
| GOAL desde UNKNOWN/OPEN/SUSPENDED | SUSPENDED, bloqueo propio pendiente |
| VAR_CHECK | SUSPENDED, añade revisión |
| GOAL_ANNULLED | SUSPENDED; si no está correlacionado, inconsistente |
| MATCH_CONTINUES | Conserva estado/bloqueos; no abre |
| Cierre interno válido con bloqueos restantes | SUSPENDED |
| Último cierre interno vigente, correlacionado y consistente | OPEN; todavía no es autorización de una operación |
| Cierre sin evidencia, expirado/futuro, versión vieja o ambiguo | Excepción/rollback, no reapertura |
| Mismo evento y contenido | Duplicado, sin nueva versión/outbox |
| Identidad reutilizada con otro contenido | UNKNOWN/inconsistente, no sobrescribe original |
| Comando de cierre ya confirmado idéntico | Devuelve estado actual; no vuelve a abrir ni muta versiones, incluso si llegó una suspensión posterior |

La vigencia de la evidencia se comprueba al decidir bajo lock. No se promete un deadline físico de commit. Cierre es una decisión histórica; su deadline no sustituye ni renueva la lease de aceptación. La reapertura inicial también requiere un bloqueo observado y cierre interno explícito; no hay apertura automática desde UNKNOWN.

## Worker y autorización existente

No se añadió otra autoridad ni comunicación REST. Se conserva `lock_market_authority` y la transacción JDBC común autorización/aceptación. Los bloqueos de GOAL se cuentan en pending_reviews, igual que VAR. La nueva versión o estado SUSPENDED invalida la lease anterior; `JdbcAtomicAuthorization` rechaza UNKNOWN, SUSPENDED, pendientes, inconsistencia y lease/versión obsoleta. Se añadió una prueba local de rechazo aun si una fuente devuelve OPEN con dos pendientes.

Flags consumidor/publicación/aceptación y Flyway permanecen false; modo atómico disabled. Fallos PostgreSQL propagan excepción y evitan confirmación falsa; Kafka conserva retries/pausa. Una caída o lag de Kafka todavía no está cubierta por un verificador de fuente: no se emiten leases automáticamente ni se autoriza por una lectura OPEN.

## Pruebas y límites

13 casos locales adicionales: GOAL desde OPEN, bloqueo GOAL+VAR y cierres separados, evidencia expirada/futura/intervalo inválido, continuación vieja/ajena/futura, versión imposible, persistencia de bloqueo/outbox, cero filas en auditoría/outbox, timestamp fuera de orden y OPEN con pendientes en Worker. Se conservan pruebas de duplicados, dos revisiones, fuente caída, lectura tras nueva instancia, conflicto de versión y rollback.

La secuencia capturada contiene dos GOAL y un VAR: ahora se comprueban tres bloqueos, sin cambiar los mensajes capturados. La primera ejecución señaló la expectativa anterior de un solo pendiente; se corrigió esa expectativa y se conserva el log fallido en la evidencia, separado de los resultados finales.

Prueba PostgreSQL preparada ampliada (NO ejecutada): GOAL → VAR → continuación, duplicado sin versión nueva, evidencia expirada sin fila de cierre, cierres parciales/finales, idempotencia, nueva instancia, outbox durable y lectura de la función autoritativa tras otro GOAL, con lease previa invalidada por estado/versión. Checkpoint de lease es fixture sintético, no verificación de Kafka. Las once pruebas atómicas de dos conexiones siguen pendientes.

Maven test y package de los tres módulos: resultados finales en `evidence/step-2-local-results.json`; Java 17 explícito, caché offline, sin infraestructura externa. Package repite la suite; no duplica casos.

| Servicio | Exitosas | Omitidas | Fallos | Errores |
|---|---:|---:|---:|---:|
| historical-archiver | 68 | 1 | 0 | 0 |
| betting-suspension-service | 105 | 1 | 0 | 0 |
| betting-worker | 95 | 12 | 0 | 0 |
| Total | **268** | **14** | **0** | **0** |

## Adopción y pendientes

- No hay contratos definitivos ni adaptador externo aprobado de cierres. Antes de conectar: aprobar semántica de GOAL/bloqueos, correlación, autoridad emisora, TTL/liveness, eventos y comandos de apuestas. La política interna de dos bloqueos GOAL+VAR privilegia seguridad y puede reducir disponibilidad hasta recibir ambos cierres explícitos.
- Cambio de API interna: todos los callers de ReviewClosure deben pasar la ventana de evidencia; se actualizaron los callers Java existentes. Comandos históricos serializados con siete campos no son intercambiables con los nuevos de nueve: no renovar expiración reutilizando command_id. Requieren revisión de adopción, sin reescribir auditoría.
- Una instalación antigua podría tener GOAL procesados sin bloqueos. No se recalculan, borran ni modifican datos automáticamente. Antes de activar este comportamiento sobre datos previos, detener admisiones y aprobar una reconciliación/migración auditada de esos mercados y permisos. Las pruebas propuestas usan bases nuevas.
- PostgreSQL real: DB dedicada aprobada, migrador/owners/roles delimitados, V1/V2 existentes, misma base para control/worker, grants, pruebas de persistencia/rollback/locks/concurrencia. No se aplicaron migraciones ni se certifica atomicidad con mocks.
- Las pruebas de reentrega Kafka/Rabbit y reinicio físico siguen pendientes. Los tests de nueva instancia solo preparan recuperación lógica; no equivalen a reiniciar un servidor.

Se puede avanzar al Paso 3 dentro del alcance que se autorice. No se puede habilitar aceptación ni declarar cerrada la integración real. Cambios limitados a Suspension y una prueba de Worker; no se tocaron contratos compartidos, infraestructura o servicios de otros desarrolladores. Sin commits/push.
