# Paso 3B — Adaptador interno VAR V2 preparado

2026-11-16. Rama `integration/Valeria-mvp-dev`, HEAD `42884b7` (avance de documentación DEV 2 respecto de `77c219f`). Se conservaron los cambios sin commit de los Pasos 1–3 y sus informes. No se ejecutaron operaciones de integración Git.

## Aprobación y alcance

Los cuatro archivos `contracts/events/*.schema.json` siguen vacíos. Los documentos del productor/Match State disponibles en este checkout siguen describiendo propuestas; no se encontró una publicación formal aprobada de VAR V2. Las reglas del usuario autorizan esta preparación interna, pero no definen por sí solas el formato de transporte, discriminador de versión ni los dos identificadores de GOAL_ANNULLED.

Resultado: **cierre de revisión V2 implementado y probado localmente como interfaz interna; integración externa V2 pendiente**. No se modificaron contratos, parsers externos, migraciones, POM, Compose, flags ni módulos de otros desarrolladores.

## Cambios funcionales

- `SuspensionService.closeReviewV2(ReviewClosure)` → `SuspensionStore.closeReviewV2` → mismo store JDBC y transacción existentes. No hay endpoint ni invocación desde Kafka/Rabbit. Requiere el mismo comando interno auditado, versión y ventana de evidencia que V1.
- `MarketState.close(..., ReferenceMode.V2_REVIEW)` exige MATCH_CONTINUES.reference_event_id **igual al event_id de la revisión VAR_CHECK seleccionada**. Una referencia ausente, al GOAL o a otra revisión no tiene fallback.
- Cada pendiente conserva su tipo GOAL/VAR_CHECK. En recuperación JDBC se obtiene de `processed_event` mediante JOIN por `(match_id,event_id)`, sin nuevas columnas ni reescribir datos. Un bloqueo GOAL no puede hacerse pasar por una revisión V2.
- Se cierra exclusivamente el pendiente seleccionado. Otros VAR y el bloqueo GOAL permanecen; solo ausencia total de pendientes permite OPEN. OPEN/CLEAR no son permiso de Worker: la autorización SQL/lease sigue siendo independiente y desactivada.
- Se conservan controles de partido, versión, última continuación observada, orden de aplicación/timestamp, evidencia vigente y consistencia. Cierres atrasados o ambiguos revierten; no se inventa reconciliación automática.
- La auditoría V2 incluye `reference_mode=V2_REVIEW` en una imagen canónica determinista. Repetir el mismo command_id/contenido devuelve el estado actual sin otra transición; cambiar su modo V1/V2 es conflicto. Repetir un cierre confirmado después de otra suspensión no reabre.

V1 conserva `closeReview` y `MarketState.close` sin modo: referencia explícita al GOAL o ausencia conforme a la política interna previa. **Su imagen canónica de nueve campos se conserva exactamente**, sin añadir modo a las filas V1 ni reescribir recibos históricos. Los bloqueos ya persistidos se identifican por su evento original. La reconciliación de GOAL históricos sin bloqueo, identificada en Paso 2, sigue pendiente de un plan aprobado; este cambio no la ejecuta.

## Lo que no se interpreta todavía

GOAL_ANNULLED conserva el comportamiento V1 fail-closed: suspende, no cierra pendientes ni reabre. No se adivina si una única referencia nombra gol o revisión ni se elige "el último VAR". Para V2 el equipo debe publicar cómo representar ambos IDs y su versión. Campos externos desconocidos siguen siendo inválidos para el decoder actual, no una evolución aditiva transparente.

El listener no distingue versiones ni transforma una continuación en cierre. Antes de conectar este adaptador: contrato/discriminador aprobado, mapeo V1/V2 explícito, autoridad emisora de evidencia y política de cierre del bloqueo GOAL. La ventana del comando es una declaración interna auditada, no una verificación de lag Kafka o un token emitido automáticamente.

## Pruebas

12 pruebas locales adicionales:

- Dos VAR pendientes, continuaciones distintas y cierres individuales.
- Cierre VAR mantiene GOAL; V2 rechaza tratar el GOAL como revisión.
- Referencias nulas, a gol, a otra revisión o desconocidas; partido incorrecto.
- Continuación externa no cierra automáticamente; continuación vieja/versión obsoleta y evidencia expirada bloquean.
- V1 conserva su referencia al gol; no se usa fallback V1 en V2.
- Cierre JDBC V2 conserva auditoría, lock y transacción; duplicado tras nueva suspensión no muta estado/outbox; mismo command_id no cambia de modo.

Se amplió la prueba PostgreSQL preparada con GOAL + dos VAR, cierres V2 separados, duplicado, nueva instancia que recupera el tipo del bloqueo y ausencia de BETTING_RESUMED mientras queda GOAL. **No se ejecutó PostgreSQL real.** La prueba preparada sigue siendo un caso omitido, no un éxito nuevo. No se certifican JOIN/locks/constraints/ACL/atomicidad mediante mocks.

Maven test y package de los tres módulos, Java 17 explícito:

| Servicio | Exitosas | Omitidas | Fallos | Errores |
|---|---:|---:|---:|---:|
| historical-archiver | 76 | 1 | 0 | 0 |
| betting-suspension-service | 126 | 1 | 0 | 0 |
| betting-worker | 103 | 13 | 0 | 0 |
| Total | **305** | **15** | **0** | **0** |

Package repite la suite; no se suman ejecuciones repetidas. Comandos exactos, hashes de logs, XML/conteos y checksums de migraciones en `evidence/step-3b-local-results.json`. `git diff --check` y revisión de alcance: satisfactorios. Fuentes de producción modificadas: SuspensionService, SuspensionStore, MarketState, ReviewClosure (enum interno) y JdbcSuspensionStore. Pruebas: VarV2MarketClosureTest (nuevo), JdbcSuspensionStoreTest y PreparedPostgresIntegrationTest. Se añaden únicamente este informe y su evidencia.

## Pendientes para cerrar DEV 3

1. Publicar/aprobar contrato VAR V2 y compatibilidad V1, incluidos GOAL_ANNULLED y discriminador de versión; aprobar semántica de cierre de GOAL y quién emite evidencia.
2. Proveer y autorizar PostgreSQL 17 exclusivo, bases nuevas y roles/owners delimitados. Ejecutar las 15 pruebas preparadas de persistencia y autorización/concurrencia; verificar recuperación real y el nuevo JOIN. Atomicidad sigue NO certificada.
3. Aprobar e implementar verificador/emisor de leases contra fuente al día; no basta OPEN ni CLEAR.
4. Aprobar contratos/topología/productor de apuestas y ejecutar integración Kafka/Rabbit/reentregas/recuperación antes de habilitar aceptación.

Sin infraestructura, migraciones reales, activaciones, commits ni push en este paso. El adaptador interno está preparado para la siguiente validación; no se declara cerrado el sistema distribuido ni compatible todo el protocolo V2 pendiente.
