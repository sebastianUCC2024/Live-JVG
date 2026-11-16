# Paso 3 — PostgreSQL real: BLOQUEADO

2026-11-16. Rama `integration/Valeria-mvp-dev`, HEAD `77c219f`. Cambios de Pasos 1/2 e informes anteriores preservados. Sin operaciones de integración Git ni commits/push.

## Entorno y autorización

`docker ps` con formato restringido finalizó con código 0 y sin filas: no había contenedores activos en el daemon consultado. No hay variables DEV3_* en este proceso. No se ha identificado una instancia PostgreSQL exclusiva con base fresca, credenciales limitadas y autorización expresa. Esto no afirma que no pueda existir PostgreSQL fuera de Docker; afirma que no hay un recurso DEV 3 verificable/autorizado para esta ejecución.

No se reutilizó `live-jvg-integration`, no se crearon bases/roles/contenedores y no se ejecutaron migraciones ni conexiones PostgreSQL. Las variables de aprobación de los tests permanecen sin activar. No hay evidencia suficiente para clasificar este paso como parcial o totalmente certificado.

## Trabajo seguro completado

1. Revisadas V1/V2 de los tres módulos, tests preparados, locks de autoridad, orden transaccional, grants y controles diferidos. Ninguna migración histórica modificada. Checksums de las seis migraciones y evidencia de builds en `evidence/step-3-local-results.json`.
2. Corregido el preflight de tests: el puerto histórico de `live-jvg-integration` **19534** no estaba bloqueado. Ahora los tres helpers rechazan 5432, 19534, 55432, 55433 y 55434, además de hosts remotos, query/fragment JDBC, nombres no dedicados y falta de aprobación/credenciales. Las reglas de forma no sustituyen una autorización ni demuestran aislamiento.
3. Separada construcción/validación de DataSource de la conexión para probar esas barreras sin red. Añadidos ocho casos locales por módulo. El preflight real comprueba PostgreSQL 17 antes de migrar; un nombre de base no certifica por sí mismo la exclusividad de la instancia.
4. Preparado un caso PostgreSQL de dos entregas **idénticas simultáneas**: una sola operación/grant, PROCESSED + DUPLICATE y contador durable de dos entregas. Antes se probaban dos operaciones concurrentes y un duplicado posterior, no este caso específico.
5. Preparada comprobación real del login worker sin superuser/CREATEROLE/CREATEDB/REPLICATION/BYPASSRLS, sin membresía en owners de relaciones DEV 3, además del preflight de permisos ya existente. No se verificaron esas propiedades en un servidor real.
6. Añadida regresión local que demuestra rechazo conservador del cierre propuesto V2 por el mapper interno V1; no se introdujo un fallback silencioso ni un consumidor V2.

No se cambió código de producción en este paso: únicamente helpers/tests y documentación dentro de DEV 3. Consumidores, publicación, aceptación y Flyway permanecen desactivados por defecto.

## Migraciones y garantías pendientes

| Esquema | Migraciones preparadas | Ejecución real en este paso |
|---|---|---|
| archiver | V1 archivo/recibos; V2 identidad `(match_id,event_id)` | No |
| betting_control | V1 mercado/ledger/cierre/outbox; V2 epoch/lease/función de lock | No |
| betting_worker | V1 operaciones/inbox/guard/audit; V2 grant y constraints diferidos | No |

Autorización y aceptación permanecen en el mismo TransactionTemplate/DataSource. Suspension tiene otra transacción, serializada por el mismo lock de market_state. READ COMMITTED; orden worker inbox → autoridad → guard → operación/evidencia/terminal. Si suspensión confirmó antes del lock, worker debe bloquear; si worker bloqueó primero, suspensión espera al commit/rollback. No es una garantía sobre VAR en tránsito ni dos llamadas REST.

Constraints diferidos exigen grant y ACCEPTED en la misma transacción/backend con lease vigente al control diferido; UNKNOWN/SUSPENDED/revisión equivocada/lease inválida no autorizan. SECURITY DEFINER usa search_path fijo y tablas cualificadas; EXECUTE público revocado. Persistencia, SQL/ACL, rollback, recuperación y concurrencia siguen **sin certificar**. El caso de permiso denegado no equivale a caída física del servidor, ni nueva instancia Java equivale a reiniciar PostgreSQL.

El bootstrap atómico preparado usa un migrador de test y un login worker separado. No demuestra el despliegue completo con dos owners NOLOGIN de producción: eso requiere provisionamiento/auditoría específicos. No declarar validados ownership ni roles a partir de mocks.

## Compatibilidad VAR V2 pendiente

| Propuesta | Estado actual | Adaptación pendiente de aprobación |
|---|---|---|
| VAR_CHECK.event_id identifica revisión | Compatible internamente | Conservar identidad por partido |
| Múltiples revisiones | Implementado como pendientes separados | Definir cierre y precedencia de eventos tardíos |
| MATCH_CONTINUES.reference_event_id apunta al VAR_CHECK | **Incompatible con interpretación actual**, que compara referencia explícita con el GOAL del pendiente | Adapter/DTO versionado que resuelva review_id explícito; no reinterpretar V1 por heurística |
| Bloqueos GOAL y VAR independientes | Implementado en Paso 2 | Acordar quién puede cerrar cada bloqueo; cierre de un VAR no libera automáticamente GOAL |
| CLEAR no autoriza | Compatible con diseño actual | Mantener separado del permiso atómico |
| GOAL_ANNULLED identifica gol y revisión | V1 solo tiene una referencia deportiva | DEV 1/equipo deben aprobar representación y versión; no inferir revisión por "la última" |
| Conservar V1 | Decoder actual permanece | Probar ambas versiones explícitamente una vez definido el discriminador y los campos V2 |

Los cuatro contratos compartidos siguen vacíos en este checkout. Las propuestas del prompt no son aprobación. No se conectaron consumidores externos ni se modificaron contratos. La nueva prueba local prueba el bloqueo ante la discrepancia, no compatibilidad V2.

## Procedimiento reproducible preparado, NO ejecutado

Primero se necesita autorización sobre una **instancia PostgreSQL 17 exclusiva de DEV 3**: host/puerto/proyecto concreto, sin bases de compañeros, credenciales limitadas, permisos para provisionar y aplicar las migraciones de estas pruebas. No crear recursos hasta obtenerla. No usar los puertos bloqueados ni cuentas del MVP. 19634 es solo un ejemplo sintáctico de puerto, no un recurso aprobado o descubierto.

El provisionamiento aprobado debe preparar cuatro bases NUEVAS:

- `dev3_it_archiver_<token>` para persistencia del archiver.
- `dev3_it_betting_control_<token>` para persistencia/cierres/outbox de Suspension.
- `dev3_it_betting_worker_<token1>` para persistencia de Worker.
- `dev3_it_betting_worker_<token2>` distinta para concurrencia atómica, con ambos esquemas inicialmente ausentes.

Logins migradores `dev3_it_*` limitados a ese entorno y login worker separado `dev3_it_worker_<token>`, sin ownership ni membresía de owners/roles administrativos. La prueba atómica requiere al migrador conceder los grants delimitados del test sobre su propia DB. Las credenciales se inyectan de forma privada, no se guardan en repo, comandos con passwords literales ni logs. No mezclar las pruebas de persistencia y atomicidad sobre una base cuyo esquema ya exista.

Después de aprobación real y provisionamiento, desde el repo:

```bash
cd /Users/valeriaestefaniagongoratorres/Live-JVG
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
```

En cada ejecución, el operador autorizado inyecta `DEV3_IT_DB_URL`, `DEV3_IT_DB_USERNAME`, `DEV3_IT_DB_PASSWORD` para la **base nueva correspondiente**. Este ejemplo comprueba presencia sin imprimir secretos. No ejecutado:

```bash
: "${DEV3_IT_DB_URL:?Falta base exclusiva aprobada}"
: "${DEV3_IT_DB_USERNAME:?Falta migrador dedicado}"
: "${DEV3_IT_DB_PASSWORD:?Falta credencial privada}"
# Solo después de obtener autorización expresa, no como sustituto de ella:
DEV3_RUN_POSTGRES_TESTS=true \
DEV3_IT_MIGRATION_APPROVAL=I_APPROVE_ISOLATED_MIGRATIONS \
mvn -f services/historical-archiver/pom.xml -Dtest=PreparedPostgresIntegrationTest test
```

Repetir con la base nueva del módulo y su POM para `betting-suspension-service` y `betting-worker`. Cada test ejecuta Flyway dentro de su base autorizada; por eso no debe ejecutarse contra bases existentes. No se hace clean/drop ni migración de datos de compañeros.

Para atomicidad, cambiar las tres variables DB a la cuarta base fresca e inyectar `DEV3_ATOMIC_WORKER_USERNAME/PASSWORD` del login limitado ya provisionado. Desde la raíz, Surefire usa el basedir del módulo y encuentra la migración de control en su módulo hermano. Ejecutar únicamente después de aprobación:

```bash
: "${DEV3_ATOMIC_WORKER_USERNAME:?Falta login worker restringido}"
: "${DEV3_ATOMIC_WORKER_PASSWORD:?Falta credencial privada}"
DEV3_RUN_ATOMIC_POSTGRES_TESTS=true \
DEV3_IT_MIGRATION_APPROVAL=I_APPROVE_ISOLATED_MIGRATIONS \
mvn -f services/betting-worker/pom.xml -Dtest=AtomicPostgresConcurrencyTest test
```

Esta suite migra control antes de worker, comprueba rol/grants y prueba ambos órdenes de suspensión/aceptación, UNKNOWN/lease vencida, versión/reinicio lógico, autoridad denegada, rollback, constraints de commit, duplicados concurrentes y deadlock aislado. No detiene PostgreSQL ni prueba caída física. No borrar esquemas para repetir: provisionar otra base nueva autorizada. Guardar XML Surefire, versión de servidor, inventario de owners/ACL y estado final, sin credenciales.

Para cerrar también requieren pruebas de pérdida/reconexión reales y recuperación de procesos bajo autorización específica. No se crean automáticamente fixtures o leases fuera de las bases dedicadas; los checkpoints sintéticos del test no prueban lag Kafka.

## Resultados de esta ejecución

Maven test y package ejecutados con Java 17 en los tres módulos. Contar una sola vez los casos que package repite. Evidencia: `evidence/step-3-local-results.json`, comandos/log hashes, casos omitidos y checksums de migraciones.

| Servicio | Locales exitosas | Omitidas PostgreSQL | Fallos | Errores |
|---|---:|---:|---:|---:|
| historical-archiver | 76 | 1 | 0 | 0 |
| betting-suspension-service | 114 | 1 | 0 | 0 |
| betting-worker | 103 | 13 | 0 | 0 |
| Total | **293** | **15** | **0** | **0** |

25 pruebas locales nuevas. Una prueba real nueva preparada aumenta las omisiones de 14 a 15; no se cuenta como éxito. PostgreSQL real: **0 ejecutadas**, 15 casos preparados bloqueados por entorno/autorización. La certificación permanece BLOQUEADA. Pasos 1/2 siguen funcionales según sus pruebas locales; no se habilita aceptación.

Para terminar DEV 3 faltan exactamente: entorno exclusivo y autorización; provisionamiento de roles/owners y bases; migraciones/pruebas PostgreSQL satisfactorias y evidencia de concurrencia; contrato VAR V2/compatibilidad V1 y apuestas aprobado; adapter de cierre y verificador/emisor de leases; integración de brokers y recuperación autorizada. Los resultados locales no eliminan esos requisitos.
