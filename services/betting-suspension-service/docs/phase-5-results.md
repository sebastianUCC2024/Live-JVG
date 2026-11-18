# Informe de resultados — DEV 3, Fase 5

Fecha: 2026-11-16 (America/Bogota). Rama `integration/Valeria-mvp-dev`, HEAD `9d8aae20a5371d41b18bda7f10bfd66f42f22094` conservados. No hubo cambios externos durante esta fase.

## Estado de los servicios y pruebas reales locales

| Servicio | Ejecutadas exitosas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|
| Historical Archiver | 68 | 0 | 0 | 1 |
| Betting Suspension Service | 89 | 0 | 0 | 1 |
| Betting Worker | 75 | 0 | 0 | 1 |
| Total | **232** | **0** | **0** | **3** |

235 casos descubiertos, 232 ejecutados. Las tres omisiones son pruebas PostgreSQL opt-in; no se habilitaron. Los tres `mvn test` y los tres `mvn package` finalizaron BUILD SUCCESS. Package vuelve a ejecutar tests: no sumar esas ejecuciones como pruebas diferentes. Se usa la copia escribible de caché existente y Maven offline; no se borraron artefactos ni se detuvieron procesos.

Se agregaron siete casos locales respecto a los 225 reportados: tres guardas de perfil, identidad por partido en archiver, anulación VAR no correlacionada, secuencia histórica real de cinco mensajes sin reapertura implícita y fallo de fuente sin fallback OPEN. Las pruebas anteriores de duplicados, conflictos, orden, rollback, recuperación lógica y estados inseguros también se ejecutaron.

Evidencias por módulo: `docs/evidence/phase-5-local-verification.txt`, con comandos, resúmenes XML y hash del JAR. Las pruebas con mocks no certifican locks PostgreSQL, confirms Rabbit ni recuperación real. No se ejecutó infraestructura externa.

## Cambios realizados

- Archiver: JDBC alineado a `(match_id,event_id)`, V2 de migración sin borrar V1/datos, readiness exige la nueva columna y regresión de identidad.
- Suspension: anulación no correlacionada impide cierre de revisión ajena; regresión de la secuencia de payloads históricos del productor.
- Worker: regresión que bloquea cuando falla una consulta, incluso con OPEN persistido todavía vigente.
- Los tres: probes Boot, perfil `dev3-integration` inactivo por defecto, conexiones explícitas, flags false, variables de ejemplo sin secretos, instrucciones y test PostgreSQL preparado con doble habilitación/validación de aislamiento.
- Sin cambios POM/dependencias de esta fase. No se modificó código ajeno ni contratos/Compose.

Listas históricas exactas conservadas en el commit previo a la limpieza: [phase-5-files.txt](https://github.com/sebastianUCC2024/Live-JVG/blob/29fc8b041f22ec1f05cea5c9a200ceba47f1fb0c/services/betting-suspension-service/docs/phase-5-files.txt), [phase-5-files.txt](https://github.com/sebastianUCC2024/Live-JVG/blob/29fc8b041f22ec1f05cea5c9a200ceba47f1fb0c/services/betting-worker/docs/phase-5-files.txt), [phase-5-files.txt](https://github.com/sebastianUCC2024/Live-JVG/blob/29fc8b041f22ec1f05cea5c9a200ceba47f1fb0c/services/historical-archiver/docs/phase-5-files.txt). Los inventarios se retiraron del árbol actual. Los informes de las fases 2–4 permanecen como evidencia histórica; los README actuales describen los cambios de preparación.

## Contratos y bloqueos

Matriz completa, configuración, solución atómica y permisos: [diseño de preparación](phase-5-integration-preparation.md).

Formato deportivo Kafka y ScoreUpdate comprobados en código/fixtures, pero los schemas compartidos siguen vacíos. Cierre VAR, comandos, topología de suspensión y evidencia de autorización no están aprobados. No existe un puente seguro entre el GET/outbox de Suspension y la aceptación del worker. Se mantiene esa integración bloqueada y desactivada.

## Carrera distribuida: solución recomendada

Una misma base PostgreSQL con esquemas separados y autorización delimitada bajo FOR UPDATE de la fila autoritativa que modifica Suspension, dentro de la **misma conexión/transacción** que confirma la operación del worker. Verificar OPEN, revisiones/inconsistencia, versión y vigencia de autoridad, y guardar autorización/operación juntos. Un commit de suspensión anterior al lock de autorización bloquea la aceptación; si autoriza primero, suspensión espera a su commit/rollback. No se presenta como orden total de eventos Kafka todavía en tránsito.

La implementación del componente y de la política de liveness/lease requiere aprobación y pruebas con dos conexiones reales. No se implementó ni habilitó para eludir ese permiso. Si hay bases separadas, una autoridad única debe finalizar decisiones y proyectarlas idempotentemente al worker; una lectura HTTP o ticket no consumido atómicamente no basta.

## Configuración y ejecución posterior

Cada `docs/INTEGRATION.md` incluye Java 17, entorno privado, perfil opt-in, healthchecks, migración/arranque y activación futura de tests. No se ejecutaron esos comandos de infraestructura. Los perfiles mantienen consumidores, publicación, aceptación y Flyway en false. Tests DB exigen autorización real, flags explícitos, URL/rol/puerto/base nuevos y esquema vacío; no crean contenedores/DB ni limpian datos.

La migración V2 del archiver no se aplicó. Esa versión de código no debe arrancarse sobre un esquema solo V1. Las V1 de los otros módulos tampoco se aplicaron.

## Git y protección

Respaldo previo verificado: `/private/tmp/live-jvg-dev3-phase5/modules-before.tar.gz` (fuentes/documentos; temporal, sin datos DB). Sin reset, stash, clean, merge, rebase, commit o push. No se cambió de rama. Se verificaron 452 hashes fuera del alcance sin diferencias y no se eliminó ningún archivo previo.

El árbol conserva los cambios locales anteriores y los de esta fase, sin commit. Inventario/scope y revisión de patrones de credenciales: `phase-5-static-review.txt`. Solo placeholders vacíos y datos sintéticos, no secretos; no se leyó `.mvp/local.env`.

## Autorizaciones exactas pendientes

1. Equipo/DEV 1: contrato deportivo, evolución, referencias VAR e identidad por partido.
2. Responsable/equipo: autoridad y política de cierre/reconciliación; ninguna reapertura ambigua.
3. DEV 3/equipo: comando simulado, topología Rabbit propia y semántica de resultados/duplicados.
4. Responsable: implementar autorización atómica (o alternativa con autoridad única), misma base y permisos delimitados; definir vigencia/liveness y punto de decisión.
5. Infra/responsable: crear bases/roles exclusivos y ejecutar migraciones revisadas (incluida V2) solo con permiso específico.
6. Responsable: pruebas reales dedicadas de DB, brokers, reinicios, carreras e idempotencia. No interrumpir recursos compartidos.
7. Activación separada y posterior: consumidores, publicación y, únicamente tras demostrar la autorización segura, aceptación.
8. DEV 2: acordar futuras consultas/UI si se desean; sus servicios y colas no necesitan modificación para esta preparación.
9. Commit/PR/push: no autorizados ni realizados.

La preparación local está terminada. La integración completa y la aceptación real simulada **no** se declaran cerradas. Detenerse y esperar aprobación.
