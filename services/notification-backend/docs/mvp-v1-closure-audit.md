# Auditoría MVP V1 y preparación conjunta

Fecha: 2026-11-16, hora local Colombia. Base auditada:
`3fb2a10e37e6a0ee820ccf2e8345e14c6c830dd8`, rama
`feature/Gabo-react-integration`. Árbol inicialmente limpio. GitHub comparó esta
base con dev como idéntica; no se ejecutó fetch ni sincronización de ramas.

## PR revisados

- #22: propuestas de schemas, correlación VAR candidata y harness V1. No activa
  un contrato V2 ni persistencia V2. Sus notas sobre fallos Dashboard anteriores
  al #24 y sobre identidad/migraciones de Archiver requieren actualización por DEV 1.
- #24: conserva los cambios de dos pruebas Dashboard para los siete módulos de CI
  y la configuración Compose actual. Verificado con 150 pruebas locales.
- #26: preparación DEV 3, sin activar aceptación. Sus resultados previos no se
  presentan como pruebas ejecutadas por DEV 2 en esta auditoría.
- #25: inverso completo del #23 en sus 17 archivos. Comparar los directorios
  frontend, notification y documentación Dashboard entre `23ada49^1` y `e44ff8d`
  no produce diferencias. Las dos correcciones Dashboard del #24 permanecen.

Inventario del #23 revertido (rutas relativas al servicio indicado):

| Servicio | Archivo | Contenido retirado |
|---|---|---|
| dashboard-backend | docs/advanced-gateway-recovery-e2e.md | Plan y evidencia local |
| dashboard-frontend | docs/advanced-aggregate-var-validation.md | Casos agregados sintéticos |
| dashboard-frontend | src/components/VarReadCompatibility.test.tsx | Dos pruebas sintéticas |
| dashboard-frontend | src/hooks/useNotificationAlerts.test.tsx | Una prueba de alerta atrasada |
| notification-backend | .dockerignore | Preparación de imagen |
| notification-backend | Dockerfile | Preparación de imagen del servicio |
| notification-backend | README.md | Enlaces de preparación |
| notification-backend | docs/advanced-notification-validation.md | Auditoría y resultados anteriores |
| notification-backend | docs/operational-preparation.md | Preparación operativa |
| notification-backend | docs/proposed-notification-ci-job.yml | Propuesta, no workflow activo |
| notification-backend | scripts/test-integration-inputs.ps1 | Guardas de preparación |
| notification-backend | scripts/test-preparation-scripts.ps1 | Pruebas de guardas |
| notification-backend | scripts/verify-local.ps1 | Ejecutor local |
| notification-backend | src/main/java/com/live_jgv/notification/domain/NotificationPolicy.java | Texto ante VAR pendiente |
| notification-backend | src/test/java/com/live_jgv/notification/NotificationHttpCompatibilityTest.java | Cuatro pruebas MVC con almacenamiento simulado |
| notification-backend | src/test/java/com/live_jgv/notification/application/AggregateSnapshotCompatibilityTest.java | Dos pruebas con dobles |
| notification-backend | src/test/java/com/live_jgv/notification/domain/NotificationPolicyTest.java | Tres casos de texto |

La reversión no retiró el consumidor, JDBC, inbox, REST, STOMP ni recuperación V1.
Compose construye Notification con el Dockerfile de la raíz: el Dockerfile local
eliminado no es un requisito de esa construcción. No se deduce del revert ni de
los títulos de los PR la motivación completa del equipo.

## Única corrección de producción

MatchState V1 conserva el estado VAR al aplicar GOAL. Por tanto, un GOAL puede
llegar con IN_PROGRESS sin usar V2. Notification lo etiquetaba como marcador
confirmado. Se cambia exclusivamente ese caso a:
«Revisión VAR en curso. Marcador actualizado: X–Y.»

Una nueva prueba falla sobre el código anterior y pasa con la corrección. No se
cambia GOAL_ANNULLED, MATCH_CONTINUES, cálculo de score, identidad ni topología.
No se restaura globalmente el #23.

## Ejecuciones locales de esta auditoría

JDK 17.0.12 existente, Maven 3.9.16 existente, cachés locales, Maven offline.
Sin instalación, perfiles externos, brokers, PostgreSQL ni migraciones.

| Validación | Resultado |
|---|---|
| Dashboard Maven verify | 150, sin fallos/errores/omitidas; BUILD SUCCESS |
| Notification antes del cambio | 103, sin fallos/errores/omitidas; BUILD SUCCESS |
| Nueva regresión antes del cambio | 6 casos de política, uno falla por el texto incorrecto |
| Notification después del cambio | 104, sin fallos/errores/omitidas; BUILD SUCCESS |
| React/Vitest | 143 aprobadas |
| TypeScript/Vite build | Correcto |
| Dashboard Node | 34 aprobadas |
| Playwright local desktop/mobile | 14 aprobadas; REST/STOMP simulados |
| Harness Python offline | 5 aprobadas; no ejecutor E2E |
| Validador de schemas propuestos | Bloqueado: falta fastjsonschema en Python disponible |

Los logs Maven se conservan en `target/urgent-v1-verify.log`,
`target/urgent-v1-regression-before.log` y `target/urgent-v1-verify-after.log` de
Notification; Dashboard conserva `target/urgent-v1-verify.log`. Playwright usa
`services/dashboard-frontend/test-results/urgent-v1-20261116/browser`.
Las suites Dashboard incluyen HTTP/STOMP local en perfil memory. No prueban
persistencia PostgreSQL, entrega Rabbit real ni el flujo completo hasta React.
El Vite temporal fue finalizado tras la ejecución.

## VAR V2: postura propuesta, pendiente de aprobación conjunta

1. Revisiones simultáneas: identidad independiente por apertura; React presenta
   el agregado autoritativo, sin reconstruir revisiones que el wire no contiene.
2. Cierre parcial: IN_PROGRESS mientras exista una revisión abierta. No aplicar
   esta interpretación al MATCH_CONTINUES V1, que despeja globalmente.
3. Cierre total: CLEAR derivado por Match State de la última revisión cerrada;
   nunca autorización de apuestas.
4. GOAL_ANNULLED: distinguir identidad del GOAL y de la revisión; el único campo
   reference_event_id actual no resuelve por sí solo ambas identidades.
5. Referencias: validar aperturas/cierres contra eventos originales. No inferir
   correlación por tiempo, proximidad o número de revisiones.
6. Discriminación V1/V2: aprobar versión, activación por partido y migración;
   no añadir campos unilateralmente a decoders estrictos.
7. Textos: respetar var_status autoritativo. Solo GOAL pendiente V1 se corrige
   ahora; la semántica V2 de anulación/cierre sigue bloqueada.
8. Recuperación: aprobar representación durable de revisiones, replay y epochs.
   stream_id/revision actuales no acreditan persistencia ni replay V2.

## Ruta crítica para el E2E conjunto

- DEV 1: confirmar orden de ScoreUpdate y custodia durante replay. Dashboard
  asigna revision por llegada: un evento nuevo pero atrasado puede reemplazar el
  marcador; deduplicación no resuelve orden causal. No ordenar por updated_at
  sin contrato. Confirmar identidad tras restauraciones y schemas aprobados.
- Infraestructura/DEV 3: aprobar proyecto, red, volúmenes NUEVOS, puertos loopback,
  roles y credenciales privadas; provisionar esquemas/permisos y migraciones por
  propietario. Conservar aceptación OFF y autorización disabled.
- DEV 2: validar schema notification y Flyway V1 solo tras autorización; habilitar
  almacenamiento y consumidor en override revisado, cola dedicada, DLX/DLQ y
  bindings score.{matchId}. Nunca competir por mvp-dashboard-scores.
- Los IT Notification requieren su base, vhost, puertos, nombres y manifiesto
  exclusivos JVG_NOTIFICATION_IT_*. No apuntarlos al Compose MVP por comodidad;
  acordar explícitamente su compatibilidad con el entorno común/harness.
- Configurar origen REST Dashboard, /ws y VITE_NOTIFICATION_ORIGIN; CORS debe
  permitir exactamente el origen frontend aprobado. No exponer secretos VITE_*.
- Registrar HTTP de catálogo/snapshot/alerts con cursores, tramas STOMP y UI sin
  recarga para gol local/visitante, VAR, anulación V1, duplicados, reconexión y
  recuperación tras reinicio. Comparar inbox, score y alert_id persistidos.
- La plantilla owner-prepared mantiene adaptadores apagados. El harness SQL no
  sustituye evidencia REST/STOMP/UI ni confirma causalidad de apuestas.

No se ejecutó infraestructura ni se certificó E2E. Los cuatro archivos de
contracts/events siguen vacíos; los schemas del #22 son propuestas. El fallo CI
HTTP 429 continúa separado: el verify offline no prueba accesibilidad de Central
ni éxito del job remoto. No se modifica Spring Boot 4.1.2 ni el workflow compartido.

PR propuesto: `fix(notification): preserve pending VAR wording for V1 goals`.
Base dev; incluir solo NotificationPolicy, su regresión y esta auditoría.
No se creó commit ni PR ni se realizó push.
