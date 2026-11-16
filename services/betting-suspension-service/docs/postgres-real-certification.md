# Certificación PostgreSQL real DEV 3

2026-11-16. **CERTIFICADO dentro del alcance de laboratorio PostgreSQL 17 probado a continuación.** No certifica despliegue productivo, Kafka/Rabbit, lag de fuente ni todo el contrato de apuestas/VAR. Rama `integration/Valeria-mvp-dev`, HEAD inicial `42884b7`, final `c1df3f7` por avance externo del equipo (productor/documentación DEV 1, ningún módulo DEV 3). No se ejecutó pull/merge; los módulos certificados no cambiaron por ese avance. Trabajo previo conservado; sin commits/push ni cambios de rama.

## Recursos creados realmente y conservados

| Recurso | Identidad |
|---|---|
| Contenedor nuevo | `live-jvg-dev3-pg17-a693ce20` |
| Imagen / versión comprobada | `postgres:17` / PostgreSQL **17.12** |
| Puerto libre comprobado antes de crear | `127.0.0.1:19634` → 5432 |
| Volumen nuevo | `live-jvg-dev3-pg17-a693ce20-data` |
| Redes nuevas exclusivas | `live-jvg-dev3-pg17-a693ce20-net` (internal), `live-jvg-dev3-pg17-a693ce20-hostnet` (bridge) |
| Bootstrap admin | `dev3_it_admin_a693ce20`, solo este contenedor nuevo |
| Migrador/owner de DB y esquemas de prueba | `dev3_it_owner_a693ce20`, LOGIN, sin SUPERUSER/CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS |
| Login worker restringido | `dev3_it_worker_a693ce20`, sin privilegios administrativos ni ownership/membresía de owners |

Bases nuevas:

1. `dev3_it_archiver_a693ce20`: prueba de archivo/persistencia y upgrade.
2. `dev3_it_betting_control_a693ce20`: prueba individual de Suspension.
3. `dev3_it_betting_worker_a693ce20`: prueba individual de Worker.
4. `dev3_it_betting_worker_atomic_a693ce20`: primer ensayo atómico satisfactorio, conservado.
5. **`dev3_it_betting_worker_atomic_a693ce20_r3`**: base autoritativa del ensayo final; **ambos esquemas betting_control y betting_worker en la misma base**, con la misma autoridad/epoch. No se simula una transacción entre bases separadas. Las bases individuales anteriores son fixtures de persistencia, no autoridades distribuidas del flujo final.

PUBLIC sin acceso CONNECT a estas bases; worker recibe CONNECT solo a sus bases atómicas. Runtime worker tiene USAGE de esquemas, SELECT/INSERT/UPDATE sobre sus seis tablas operativas y EXECUTE de funciones delimitadas; sin DML en origen/grants ni CREATE de esquemas. La tabla exclusiva de deadlock tiene permisos SELECT/UPDATE adicionales solo como fixture. Migrador es owner porque crea las tablas/funciones; no es el login operativo worker. Owners NOLOGIN separados y credenciales/ACL del despliegue final siguen requiriendo su propia revisión.

Credenciales generadas aleatoriamente **fuera de Git**, en `/private/tmp/live-jvg-dev3-cert-run/private.json` y `postgres.env`, archivos modo 0600 y directorio 0700. No se publican passwords. Logs privados, scripts de provisionamiento y XML originales permanecen en ese directorio. No ejecutar `docker inspect` completo con variables privadas para publicar evidencia.

Se inspeccionaron contenedores incluidos detenidos, volúmenes y redes antes de crear. Todos los comandos de escritura Docker apuntaron a estos recursos nuevos; no se inició, borró ni reutilizó `live-jvg-integration`, MVP o entornos históricos. El puerto se verificó con bind local antes de asignarlo. Recursos nuevos permanecen activos/conservados, incluido el volumen después de reiniciar.

## Ejecución y único problema encontrado

La primera conexión desde Maven fue rechazada: con la red `--internal`, Docker 29.8.0 mostró `5432/tcp: []` aunque existía HostConfig.PortBindings. Los cuatro jobs fallaron antes de migrar (incluido el bootstrap de la suite atómica); no se cuentan como pruebas funcionales aprobadas. Se conservan causas/log hashes en results.json y logs originales r1.

Se creó otra red bridge exclusiva y se conectó **solo el contenedor nuevo**. Docker pasó a publicar el puerto loopback. No se relajaron guards ni se sustituyó JDBC por mocks. Segundo ensayo: tres pruebas de persistencia y doce atómicas satisfactorias. Después se añadieron dos comprobaciones reales y se creó otra base fresca para repetir la suite atómica ampliada. No se borró/reutilizó un esquema para eludir los guards.

No se detectó defecto de código productivo ni de SQL que requiriese corrección. Los dos cambios de pruebas adicionales están en `betting-worker/.../AtomicPostgresConcurrencyTest.java`; no se tocó VAR V2 ni ninguna migración histórica.

## Migraciones realmente ejecutadas

| Base | Flyway ejecutado y registrado con success=true |
|---|---|
| Archivo | archiver V1 → fixture V1 → V2, conserva evidencia e identidad compuesta |
| Control individual | betting_control V1 y V2 |
| Worker individual | betting_worker V1 y V2 |
| Cada base atómica | betting_control V1/V2 antes de betting_worker V1/V2 |

Las seis migraciones fuente tienen los mismos SHA-256 que al iniciar la tarea. Catálogos reales de tablas, índices, claves y constraints, owners, funciones SECURITY DEFINER/search_path y triggers diferidos: `evidence/postgres-certification/final-after-restart.json`. Ambas restricciones de autorización/aceptación están habilitadas y son DEFERRABLE INITIALLY DEFERRED. `roles.json` registra atributos sin passwords.

## Pruebas PostgreSQL finales

| Suite seleccionada | Ejecutadas | Exitosas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|---:|
| Archiver PreparedPostgresIntegrationTest | 1 | 1 | 0 | 0 | 0 |
| Suspension PreparedPostgresIntegrationTest | 1 | 1 | 0 | 0 | 0 |
| Worker PreparedPostgresIntegrationTest | 1 | 1 | 0 | 0 | 0 |
| AtomicPostgresConcurrencyTest final | 14 | 14 | 0 | 0 | 0 |
| **Total único final** | **17** | **17** | **0** | **0** | **0** |

El ensayo atómico previo de 12 casos también pasó: no se suma otra vez al total de casos únicos. Las cuatro ejecuciones iniciales con errores de conexión se registran separadas; el total final anterior no las oculta ni las convierte en éxitos.

Evidencias concretas, ejecutadas contra PostgreSQL real:

- Suspensión obtiene lock primero: autorización espera y termina BLOCKED tras su commit.
- Worker obtiene lock primero: suspensión espera hasta su commit; ACCEPTED se confirma antes de la suspensión. El resultado SUSPENDED posterior no revoca retrospectivamente esa operación. El orden de serialización queda probado; no se afirma orden respecto a VAR aún en tránsito.
- Dos operaciones concurrentes y dos entregas idénticas simultáneas: un comando duplicado produce una operación/grant, dos entregas registradas y PROCESSED+DUPLICATE.
- UNKNOWN, SUSPENDED, lease expirada y revisión incompatible no aceptan. OPEN con lease válida puede confirmar ACCEPTED en el fixture aislado.
- Rollback tras grant, expiración antes del commit y dos constraints diferidos: sin autorización de la transacción actual no hay ACCEPTED, y un grant no puede confirmarse solo.
- Pérdida **real** de conexión después de registrar grant: `pg_terminate_backend(pg_backend_pid())` termina únicamente la conexión del propio test. No quedan operación, grant ni inbox confirmados. No se detiene otra sesión/servidor.
- Escritura directa del worker en autoridad o grants: PostgreSQL devuelve **42501 insufficient_privilege**, no un error distinto enmascarado.
- Autoridad sin permiso EXECUTE produce rollback; deadlock real 40P01 se reintenta en otra transacción.
- Persistencia de archivo/identidades, operaciones/consulta, cierres/idempotencia/bloqueos y outbox transaccional; nueva instancia lee estado durable. La prueba de Suspension incluye el escenario interno V2 ya preparado, sin modificarlo.

XML originales preservados y metadatos/casos/duración/hashes de logs en `evidence/postgres-certification/results.json`. Los cuatro logs finales Maven están copiados como `*-real-maven.txt`, revisados contra los valores privados de passwords antes de publicarlos. Los errores de conexión y de rollback esperados dentro de tests no significan fallo de la suite.

## Reinicio físico y comprobaciones finales

Se reinició realmente **solo** `live-jvg-dev3-pg17-a693ce20`; PostgreSQL volvió a aceptar conexiones. Se compararon antes/después conteos y MD5 deterministas de **40 tablas** en las cuatro bases del ensayo final, incluidos Flyway, ledger, outbox, leases, operaciones y grants; catálogos/índices/constraints también iguales. `restart-result.json` registra el resultado. La base del ensayo preliminar se conserva adicionalmente.

En la base autoritativa final permanecen **4 ACCEPTED y 4 grants**, con cuatro transaction_id y backends distintos. Los controles posteriores al reinicio comprueban cero ACCEPTED sin grant y cero grants sin ACCEPTED (`final-invariants.json`). Hay un RECEIVED sembrado explícitamente por el caso SQL de grant separado; es un fixture negativo intencional, sin autorización ni aceptación, no una operación parcialmente confirmada por el flujo normal.

## Regresión local y estado de código

Java 17 explícito, Maven offline y POM individual. Maven **test y package** satisfactorios en los tres módulos; `git diff --check` satisfactorio.

| Servicio | Exitosas locales | PostgreSQL omitidas por defecto | Fallos / errores |
|---|---:|---:|---|
| historical-archiver | 76 | 1 | 0 / 0 |
| betting-suspension-service | 126 | 1 | 0 / 0 |
| betting-worker | 103 | 15 | 0 / 0 |
| **Total** | **305** | **17** | **0 / 0** |

Son dos ejecuciones separadas: las 17 omisiones de la suite por defecto **sí se ejecutaron explícitamente** en las suites PostgreSQL anteriores, sin omisiones allí. No se cuenta package dos veces. Los dos casos nuevos elevan las pruebas PostgreSQL de 15 a 17. Los flags de consumidor/publicación/aceptación y modo atómico predeterminado siguen apagados. No se arrancaron servicios ni apuestas fuera de fixtures aislados.

Cambios de esta tarea: dos tests en AtomicPostgresConcurrencyTest, este informe y evidencia de certificación; los cambios anteriores de DEV 3 permanecen. No se modificaron fuente productiva, POM, infraestructura principal, contratos compartidos, VAR V2 o migraciones.

## Repetición y límites

Se conservan contenedor/volumen/redes/bases/roles y configuración privada local. Para repetir los tests que exigen esquema vacío se deben crear **otras bases nuevas** con los prefijos admitidos en este mismo contenedor exclusivo y conceder CONNECT al worker solo en la nueva base atómica; no limpiar las actuales. Los scripts privados de esta ejecución muestran comandos/variables usados; no relanzar provision.py ni run-real-r2.py sobre bases ya migradas. Mantener credenciales privadas, migraciones inmutables y XML/logs separados por ejecución.

Bloqueos restantes fuera de esta certificación: publicación/aprobación de contratos VAR V2 y apuestas; emisor/verificador de leases con catch-up Kafka; topología/productor de comandos; integración Kafka/Rabbit y recuperación de los servicios completos; reconciliación de historiales anteriores a los bloqueos GOAL y revisión del despliegue/owners definitivos. Una lectura OPEN/CLEAR no autoriza apuestas. La certificación PostgreSQL de laboratorio no habilita aceptación ni declara cerrado todo DEV 3.
