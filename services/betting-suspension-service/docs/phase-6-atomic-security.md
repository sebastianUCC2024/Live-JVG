# DEV 3 — Fase 6: autorización transaccional y cierre de seguridad

Fecha: 2026-11-16, America/Bogota. Implementación preparada e inactiva. No se ejecutaron migraciones, PostgreSQL, brokers o contenedores. Los resultados locales y el inventario están en `phase-6-results.md`.

## 1. Decisión y condiciones de atomicidad

Los defaults apuntan a **bases distintas**: betting_control y betting_worker. Compartir un servidor no permite una transacción JDBC común entre ellas. No se inspeccionaron catálogos, roles o propietarios reales porque no hay autorización de infraestructura; no se afirma que estén aprovisionados correctamente.

Se implementa un modo opt-in `postgres-atomic` para el caso aprobable de **una misma base PostgreSQL 17**, esquemas separados y roles delimitados. Fuente autoritativa: betting_control.market_state. Suspension conserva su transacción; worker usa otra, y ambas serializan por la misma fila del partido. Lo que realmente forma una única transacción es **autorización + aceptación + evidencia + inbox/historial del worker**, no dos procesos o llamadas REST.

Sin esa base/autoridad/roles el modo permanece disabled y ningún OPEN leído puede autorizar: el worker devuelve BLOCKED/ATOMIC_AUTHORIZATION_REQUIRED incluso si se activa por error la antigua elegibilidad con evidencia. No se conectó un GET HTTP como sustituto de atomicidad.

## 2. Componentes implementados

### Suspension, V2__atomic_authority.sql

- authorization_authority: epoch persistente de la autoridad, asignado una vez por migración. No se rota automáticamente ni se considera un secreto.
- authorization_lease: versión de mercado, lease_id, emisión/vencimiento con reloj DB, checkpoint, actor y motivo. Máximo 5 segundos.
- lock_market_authority(text): función SECURITY DEFINER con search_path=pg_catalog y referencias cualificadas. Bloquea market_state con FOR UPDATE dentro de la transacción **del caller** y devuelve estado, versión, pendientes, inconsistencia, epoch, lease y reloj/base reales. No abre otra conexión ni hace COMMIT.
- EXECUTE público revocado. Los roles aprobados deben recibir grants explícitos; el login worker no necesita escritura en el origen.
- JdbcOpenLeaseIssuer: componente interno que toma el mismo lock, exige OPEN consistente, cero revisiones y expectedVersion, y guarda una lease corta con reloj DB. **No está registrado como bean, endpoint o scheduler** y no hay renovación automática.

El checkpoint es una declaración auditable del caller, **no una verificación implementada de lag/catch-up Kafka**. Debe aprobarse y construirse el caller/verificador antes de emitir leases reales. Nunca renovar por un heartbeat aislado ni usar updated_at histórico como permiso.

Cada evento/cierre cambia la versión del agregado; la lease debe coincidir exactamente. Una suspensión confirmada o una nueva revisión invalida el permiso aunque no haya vencido temporalmente. MATCH_CONTINUES sigue sin cerrar revisiones por Kafka.

### Worker, V2__transactional_authorization.sql

- operation_authorization: identidad/canonical del comando, operación/partido, versión autoritativa, epoch/lease, vigencia, database_name, backend_pid y transaction_id.
- record_atomic_authorization(...): función SECURITY DEFINER que obtiene de nuevo el lock de origen en la misma conexión y verifica OPEN, revisión, epoch, lease, pendientes y tiempo DB antes de insertar prueba. El caller no elige contenido de mercado, backend, transacción o deadline.
- Trigger diferido accepted_requires_atomic_authorization: un ACCEPTED debe tener prueba vigente del mismo comando/contenido/partido, **backend y transacción actuales**. Un permiso previamente confirmado en otra transacción no sirve.
- Trigger diferido authorization_requires_accepted_operation: una prueba tampoco puede confirmarse sin su operación ACCEPTED. Si falla cualquier parte, ambas revierten.
- Los triggers vuelven a comprobar vigencia al ejecutar el control diferido antes del commit; no usan transaction_timestamp congelado. Se prohíbe cambiar la temporización de constraints desde el camino de aplicación. No se promete un deadline de tiempo físico entre la última comprobación DB y el commit efectivo ni protección frente a un administrador que desactive triggers.
- V2 se detiene si existen ACCEPTED de V1 sin una revisión explícita de migración: no se inventa autorización retrospectiva.

JdbcAtomicAuthorization exige una transacción Spring activa, writable y con **el mismo DataSource ligado**. La sesión guarda la identidad de ese recurso: fuera de la transacción o en otra, rechaza persistir. No usa REQUIRES_NEW ni autocommit para conceder. La operación terminal se actualiza después del grant dentro del mismo TransactionTemplate.

En modo atómico no se consulta el puerto de lectura externo. La renovación de lease de la misma versión es admisible solo tras comprobar la autoridad DB bloqueada; no convierte una lectura cacheada o notificación Rabbit en permiso. El guard local conserva versión/epoch y no se reinicia ni se sobreescribe ante una regresión de autoridad. Un cambio de modo sobre guards antiguos requiere reconciliación, no un borrado automático.

## 3. Orden, aislamiento y tiempos

Orden uniforme worker: reclamar command_id → bloquear mercado autoritativo mediante función → bloquear/crear guard local → insertar operación RECEIVED → evaluar/registrar autorización → persistir evidencia/terminal/historial/inbox → commit → ack.

Suspension y lease issuer: mercado autoritativo → revisiones/ledger/lease → commit. No toman locks del worker ni llaman al worker. Outbox usa sus propias filas y no invierte ese orden.

Las configuraciones JDBC de los dos servicios fijan READ COMMITTED y timeout transaccional de 5 segundos. Los constructores usados por tests pueden recibir un template explícito; los tests de expiración diferida usan una transacción exterior sin ese timeout para aislar el trigger. Deadlock, serialization o lock timeout provocan rollback completo; se reintenta desde el límite de transacción, no continuando una transacción PostgreSQL abortada. Rabbit mantiene sus cuatro intentos y luego stop/requeue, sin bucle de reinicio automático.

Garantía condicional: si la suspensión hizo commit antes de que worker consiga el lock, worker no puede aceptar ignorándola. Si worker lo consigue primero, suspensión espera hasta commit/rollback del worker. Ese es el orden de linealización. No es una garantía sobre el instante físico de VAR ni sobre mensajes todavía en tránsito. El contrato del negocio debe aprobar ese punto de decisión y la política de liveness/lease; sin ello no hay leases reales ni aceptación activa.

## 4. Base, roles y propiedad: requisitos exactos

No se crearon ni alteraron roles reales. Propuesta para DBA, nombres ilustrativos que requieren aprobación:

| Rol | Propiedad/capacidad |
|---|---|
| dev3_control_owner (NOLOGIN) | Schema/tablas/función de betting_control; dueño no superusuario |
| dev3_worker_owner (NOLOGIN) | Schema/tablas/funciones/triggers de betting_worker; dueño no superusuario |
| dev3_control_app (LOGIN) | DML necesario sobre estado/ledger/revisiones/outbox/lease; SELECT de epoch; sin DDL, rotación de epoch o roles |
| dev3_worker_app (LOGIN) | DML de guard/inbox/operation/historial/evidence/audit; EXECUTE de funciones controladas; sin DML en control ni en operation_authorization |
| Migrador autorizado | SET ROLE/propiedad necesaria para Flyway; separado de logins de aplicación |

Misma base concreta y esquema fijo; no usar el usuario del MVP ni app como dueño/superusuario. Aprobar permisos de creación antes de Flyway. Asegurar propietario correcto de **cada tabla, función y trigger** después de migrar; no basta cambiar el dueño del schema. Mantener el historial Flyway accesible solo al migrador correspondiente.

Grants mínimos ilustrativos, **no ejecutados**, una vez creados roles/esquemas y asignados sus dueños:

```sql
GRANT USAGE ON SCHEMA betting_control TO dev3_worker_owner, dev3_worker_app;
GRANT EXECUTE ON FUNCTION betting_control.lock_market_authority(text)
  TO dev3_worker_owner, dev3_worker_app;
GRANT USAGE ON SCHEMA betting_worker TO dev3_worker_app;
GRANT SELECT, INSERT, UPDATE ON betting_worker.market_guard, betting_worker.command_inbox,
  betting_worker.operation, betting_worker.operation_transition, betting_worker.operation_evidence,
  betting_worker.message_audit TO dev3_worker_app;
GRANT EXECUTE ON FUNCTION betting_worker.record_atomic_authorization(text,text,bigint,uuid,uuid)
  TO dev3_worker_app;
```

No hacer GRANT ALL ni INSERT/UPDATE/DELETE/TRUNCATE a worker sobre authorization o tablas fuente, ni CREATE en schemas. La escritura de authorization solo corresponde a la función controlada. Revocar grants heredados excesivos y comprobar membresías. El owner worker necesita EXECUTE de la función de control para su llamada SECURITY DEFINER.

verifyInstallation valida base, EXECUTE de ambas funciones y ausencia de capacidades peligrosas en el login worker. database/epoch esperados son obligatorios al configurar postgres-atomic; cada lock verifica que coincide la autoridad. No sustituye una auditoría de owners/membresías ni comprobar que **Suspension esté configurado hacia esa misma base/epoch**. Las funciones deben corresponder a las migraciones revisadas, no a una réplica/copias divergentes de las tablas.

Requisitos de despliegue no comprobados: PostgreSQL 17, una DB común dedicada para control/worker; esquemas/roles/owners anteriores; relojes DB disciplinados; leases/verificador de fuente aprobados; migraciones verificadas y pruebas con conexiones restringidas. Archiver puede seguir en otra base, no participa en la autorización.

## 5. Defaults y ejecución futura

No cambian los flags false de consumo/publicación/aceptación, ni Flyway. Se añade:

- worker.authorization.mode=disabled (también fijado en dev3-integration).
- BETTING_WORKER_AUTHORIZATION_MODE, futuro valor postgres-atomic, **no activado**.
- BETTING_WORKER_AUTHORITY_DATABASE y BETTING_WORKER_AUTHORITY_EPOCH, vacíos por defecto; deben provenir de inspección DBA autorizada.

Activar modo no activa aceptación ni configura un renovador de leases. Activar solo la antigua propiedad de aceptación tampoco puede autorizar por lectura: sin sesión atómica queda bloqueado. No hay endpoint para forzar OPEN.

Antes de arrancar esta versión se necesitan V2 de Suspension y Worker, además de las anteriores. Sus checks de esquema exigen las nuevas tablas. No se reescribieron V1; Archiver conserva sus V1/V2 de Fase 5.

Orden futuro: aprovisionar DB/roles → migrar control bajo su owner → migrar worker bajo su owner → asignar/validar owners/grants → obtener epoch/DB autoritativos → probar con rol restringido → aprobar liveness/caller de lease → activar etapas por permisos separados. No hay un comando de un solo paso que habilite todo. No ejecutar hoy.

Healthchecks/profiles/variables/DB tests de Fase 5 siguen preparados y apagados. Health UP no certifica lag, leases válidas, cierre VAR ni atomicidad.

## 6. Pruebas: local frente a PostgreSQL pendiente

Locales: vínculo de sesión al recurso transaccional real de Spring sobre conexión mock, rechazo de transacción ausente/read-only/nueva, fallo de autoridad, expiración, permisos excesivos, orden grant → terminal → commit, rechazo del antiguo permiso por lectura, contador de guard estable, emisor de lease y regresión de los tres servicios. Mocks no certifican PostgreSQL.

Se añade AtomicPostgresConcurrencyTest, **deshabilitado por defecto**, con dos conexiones y roles diferenciados. Prepara:

1. Suspensión obtiene lock antes de autorización: worker espera y bloquea tras su commit.
2. Worker obtiene lock antes de suspensión: suspensión espera al commit; se observa orden durable.
3. Operaciones concurrentes y duplicado sin otra operación/grant.
4. UNKNOWN, OPEN expirado, versión equivocada y nueva instancia leyendo resultado durable.
5. Autoridad inaccesible (permiso revocado solo al rol dedicado durante el caso, restaurado en finally): rollback sin operación. No se detiene un servidor real.
6. Rollback después del grant: ni operación ni autorización quedan confirmadas.
7. Vencimiento entre grant y commit: comprobación diferida revierte ambas filas.
8. Aceptación directa sin grant y grant confirmado sin aceptación: rechazo al commit.
9. Deadlock deliberado solo sobre tabla de prueba aislada, víctima SQLSTATE 40P01 y reintento en transacción nueva. No altera partidos ni recursos compartidos.

Para ejecutarla requiere autorización posterior: DEV3_RUN_ATOMIC_POSTGRES_TESTS=true, DEV3_IT_MIGRATION_APPROVAL=I_APPROVE_ISOLATED_MIGRATIONS y variables del test aislado anterior. Base NUEVA dev3_it_betting_worker_<token>, puerto local no-MVP, owner dev3_it_* y rol ya creado DEV3_ATOMIC_WORKER_USERNAME (prefijo dev3_it_worker_) y password privado. Solo activar este test por -Dtest=AtomicPostgresConcurrencyTest: no mezclarlo con el otro test que exige schema inicialmente vacío.

El test aplica ambas migraciones dentro de esa DB, concede permisos de objetos al rol dedicado y crea una tabla de deadlock de test; no crea roles/DB, no inicia contenedores ni borra datos. Esas acciones también requieren el permiso real, no solo variables con texto de aprobación. Los fixtures OPEN/checkpoint son sintéticos y no prueban catch-up Kafka. Las funciones se crean por el migrador del test: producción debe usar dueños NOLOGIN y auditar sus ACL explícitamente.

Comando de referencia, **no ejecutado** y solo después de permisos/entorno privados:

```bash
DEV3_RUN_ATOMIC_POSTGRES_TESTS=true DEV3_IT_MIGRATION_APPROVAL=I_APPROVE_ISOLATED_MIGRATIONS \
  mvn -f services/betting-worker/pom.xml -Dtest=AtomicPostgresConcurrencyTest test
```

Hasta ejecutar y evaluar esas pruebas, SQL/migraciones/locks/roles/atomicidad real permanecen **no certificados**. Tampoco se certifica caída física o reinicio de PostgreSQL con mocks ni con el caso de permiso inaccesible.

## 7. Contratos y Kafka/RabbitMQ

Propuesta final para aprobación: `contracts/final-contract-proposal.md`, schemas locales de MatchEvent y betting-state, y schema local de comando en worker/docs/contracts. No se modificó contracts/events.

Kafka conserva match_events, clave match_id, grupos propios y fuentes distintas; no crear topics/cambiar particiones automáticamente. Mantener correlación VAR explícita, consumidores apagados y retención suficiente para pausa/retry. Rabbit necesita exchange/cola/routing propios aprobados, permisos, mandatory/confirm/outbox donde corresponde y no competir con dashboard. Notificación BETTING_RESUMED y CLEAR no son permisos para worker.

Pruebas end-to-end de brokers/ack/reentrega siguen pendientes; no se introdujo publicación automática ni dinero real.

## 8. Autorizaciones y riesgos restantes

Se puede revisar e integrar el código preparado; no puede declararse aceptación habilitable solo por las pruebas locales.

1. Aprobar contratos y semántica de correlación/cierre/liveness/ventana de lease.
2. Elegir DB común; crear roles y asignar owners/grants con revisión DBA. Si se conservan bases separadas, NO usar este modo ni REST como atomicidad: mantener BLOCKED hasta una autoridad única con decisión/finalización durable aprobada.
3. Autorizar y ejecutar migraciones nuevas y tests PostgreSQL de concurrencia/rollback/ACL/expiración. Revisar datos V1 aceptados si los hubiera; no inventar pruebas retrospectivas.
4. Construir y aprobar el caller/verificador que emite leases; no existe uno conectado ni una prueba de catch-up certificada.
5. Autorizar integración real Kafka/RabbitMQ dedicada, luego activaciones de consumo/publicación y finalmente aceptación, por separado y con evidencias satisfactorias.
6. Auditoría de acceso/retención/backup y relojes. La garantía no cubre un evento VAR todavía en tránsito ni un administrador que altere tablas/triggers/epoch durante operación; rotación/reconciliación requieren parar admisiones y un plan auditado.
7. Commits/push/Compose ajeno no autorizados ni realizados.
