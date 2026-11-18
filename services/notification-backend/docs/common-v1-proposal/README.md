# Propuesta common-v1: datos, permisos y Notification

**PROPOSED_NOT_APPROVED. No se ha ejecutado SQL, Flyway, Docker ni brokers.**
Base de fuentes: `257662cc6fb5c300e01e5ad4ad967566dd60af88` (PR #32/#33).
Todo este paquete pertenece a documentación DEV 2; no cambia módulos, contratos,
generadores, migraciones ni overlays de DEV 1/DEV 3. No autoriza iniciar servicios.

## Destinos y alcance

- Run: `5c84548cf006`; proyecto exclusivo NUEVO `live-jvg-e2e-5c84548cf006`.
- PostgreSQL 17 nuevo: conexión host propuesta `127.0.0.1:55991`, base `live_jvg`.
- Rabbit nuevo: `127.0.0.1:56991`, management `127.0.0.1:15991`, vhost `/`.
- Kafka/topic de common-v1 y sus permisos siguen bajo DEV 1; no los aprovisiona este paquete.
- La base `live_jvg` existe vacía por la inicialización autorizada del contenedor.
  El SQL propuesto la adopta, no ejecuta CREATE DATABASE ni cambia su nombre.
  El nombre genérico y el vhost `/` solo son admisibles en servidores exclusivos
  nuevos acreditados. Loopback, puertos alternativos o un nombre de proyecto NO
  demuestran por sí solos aislamiento. Nunca usar PostgreSQL nativo o laboratorios
  previos, ni reutilizar volúmenes; tampoco se propone borrarlos.

No se importa, ejecuta ni adapta por sustitución el generador standalone de DEV 3.
Este usa `dev3_it_<run>_authority`; sus bases, puertos y nombres no se renombran a
`live_jvg`. Los scripts se derivan directamente de las catorce migraciones comunes
originales y conservan propietarios separados.

## Archivos y herramientas

| Archivo | Propósito, todavía sin autorización de aplicación |
|---|---|
| prepare_common_v1.py | Solo lectura/render de texto local con biblioteca estándar; no conecta ni ejecuta procesos |
| plan.proposed.json | Destinos, roles, flags OFF, migraciones y hashes de fuentes |
| 10-provision.proposed.sql | Adopción segura de base vacía, owners/migrators/runtime y schemas |
| 20-cross-owner.proposed.sql | Únicamente el owner Worker puede invocar lock_market_authority del Control |
| 30-passive-grants.proposed.sql | Revoca permisos directos runtime y concede solo SELECT/USAGE |
| 40-fixtures.proposed.sql | Inserta dos catálogos y comprueba baseline vacía; no cambia epochs |
| 50-select-preflight.proposed.sql | SELECT y control psql; no DDL, DML, nextval, setval, locks ni funciones de autoridad |
| 60-future-runtime-dml.proposed.sql | Permisos futuros separados para ensayo V1, sin autoridad; NO aplicar en fase pasiva |
| notification-rabbit.proposed.json | Identidad y ACL por recurso; no es un archivo de importación automática Rabbit |
| test_prepare_common_v1.py | Validaciones negativas y contraste con las migraciones reales |
| SHA256SUMS | Integridad de artefactos y fuentes de propuesta; no equivale a aprobación |

Comandos **offline permitidos**, desde la raíz de este worktree:

```powershell
python services/notification-backend/docs/common-v1-proposal/prepare_common_v1.py --run 5c84548cf006 --check
python -m unittest discover -s services/notification-backend/docs/common-v1-proposal -p test_prepare_common_v1.py -v
git diff --check
```

`--render` solo escribe propuestas en este directorio. No existe comando apply,
execute, migrate ni conexión en el generador. Cambiar fuentes o destinos exige
regenerar, revisar y obtener aprobación de las nuevas huellas.

## Orden exacto futuro, sujeto a aprobación

0. Operador acredita servidores y volúmenes nuevos, endpoints, ausencia de datos
   compartidos y huellas de instancias PostgreSQL **y Rabbit**. Acreditar también
   que la base administrativa postgres no contiene aplicaciones ajenas. PostgreSQL
   system_identifier no identifica Rabbit ni demuestra la exclusividad por sí solo.
1. Revisar manifest, SQL y hashes. Mantener todos los flags OFF del overlay DEV 1.
2. DBA, conectado explícitamente a `live_jvg` en el endpoint aprobado, revisa/aplica
   `10-provision`. No ejecutar sobre la base administrativa postgres. El primer
   propietario de la base debe ser el propio usuario DBA de inicialización.
3. Asignar credenciales privadas a los logins, sin SQL con passwords en Git ni
   secretos en argumentos/logs. Owners son NOLOGIN; migrator y runtime distintos.
4. Ejecutar Flyway, con herramienta existente aprobada, en este orden:

| Orden | Servicio / schema | Migraciones, en orden | Historia independiente |
|---|---|---|---|
| 1 | Match State / match_state | V1 durable_match_state → V2 quarantine_and_replay → V3 custody_integrity ? V4 live_alert_outbox | match_state.flyway_schema_history |
| 2 | Dashboard / dashboard | V1 dashboard_projection → V2 persistent_stream_epoch | dashboard.flyway_schema_history |
| 3 | Notification / notification | V1 notification_inbox_and_alerts ? V2 live_alert_identity | notification.flyway_schema_history |
| 4 | Archiver / archiver | V1 archive_events → V2 match_scoped_identity | archiver.flyway_schema_history |
| 5 | Control / betting_control | V1 betting_control → V2 atomic_authority | betting_control.flyway_schema_history |
| — | DBA | 20-cross-owner, después de Control V2 y antes de Worker | Sin migración compartida nueva |
| 6 | Worker / betting_worker | V1 simulated_operations → V2 transactional_authorization | betting_worker.flyway_schema_history |

Cada ejecución Flyway utiliza su login migrator, `initSql=SET ROLE <owner propio>`,
`defaultSchema` y `schemas` con un único schema, `createSchemas=false`,
`cleanDisabled=true` y `locations` apuntando SOLO al directorio original de ese
servicio. Los paths exactos y hashes UTF-8/LF están en plan.proposed.json. Ejecutar
validate antes/después según el procedimiento aprobado. No consolidar las catorce
migraciones en una historia: hay versiones V1/V2 repetidas. SQL preflight comprueba
versiones/success, pero no reemplaza la comprobación de checksums de Flyway.
Los owners deben poseer sus funciones SECURITY DEFINER y tablas; mantener
`search_path=pg_catalog`, REVOKE PUBLIC y los triggers diferidos de Worker V2.

5. DBA aplica `30-passive-grants`; las migraciones no dan privilegios runtime.
6. DBA aplica `40-fixtures` solo ANTES de publicar eventos. Conflicto o datos
   anteriores provocan rechazo y rollback; nunca reset automático.
7. DBA ejecuta `50-select-preflight` con sesión read-only. Mantener consumos OFF.
   Revisar los resultados y Flyway validate; esta fase no necesita otorgar DML runtime.
8. Responsable Rabbit prepara identidad/topología/ACL según JSON, después de
   autorización específica; actualiza SOLO la configuración privada autorizada de
   Notification para usar el nuevo username y contraseña. No cambiar `mvp` global.
9. Revisión conjunta de configuración efectiva, readiness pasiva y evidencia.
10. **DETENERSE.** `60-future-runtime-dml`, activación de consumidores/storage,
    publicación sintética, reinicios y E2E requieren otro permiso. Ninguno de estos
    artefactos activa aceptación, autoridad ni leases.

El archivo 60 se niega a continuar sin la variable adicional
`runtime_dml_approved_run=blocked-only:5c84548cf006`. La aprobación de provisión
pasiva no basta para conceder DML. Esta variable tampoco autoriza iniciar servicios.

## Guardas y aprobación privada

Todos los SQL son propuestas psql, no ejecutadas ni comprobadas por PostgreSQL.
Antes de DDL/DML exigen variables `operator_approved_project`,
`expected_system_identifier` y `adopt_empty_database=true`. La conexión debe ser
DBA explícita a `127.0.0.1:55991/live_jvg`, sin heredar PGHOST/PGPORT/PGSERVICE,
PGOPTIONS o secretos de otro laboratorio. No se entrega un wrapper que conecte.

Las guardas comprueban PG17, base exacta, huella pg_control_system y proyecto;
rechazan otras bases de aplicación y schemas ajenos. La primera adopción exige
base sin relaciones, funciones o tipos de aplicación, sin comentario previo y
sin colisiones de roles del run. Después exige el comentario de base de este
run/cluster; no puede usarse para adoptar arbitrariamente un entorno existente.
La marca es evidencia operativa modificable por DBA, no una identidad criptográfica.

El operador puede copiar plan.proposed.json a un archivo privado y completar
EXCLUSIVAMENTE operator_approval con estos campos, usando observaciones reales:

```json
{
  "approved_by": "<operador responsable>",
  "system_identifier": "<identificador real del cluster nuevo>",
  "new_isolated_server": true,
  "exclusive_project": true,
  "adopt_empty_database": true,
  "postgres_major": 17
}
```

Los placeholders anteriores son deliberadamente inválidos. No se generó aprobación
real ni credenciales. `--validate-approval <archivo privado>` solo valida estructura,
destinos, hashes y flags offline; no autentica al firmante ni comprueba el servidor,
ni da permiso para aplicar. La acreditación Rabbit y las aprobaciones de cada owner
son anexos independientes del operador. Sin ellas debe detenerse el procedimiento.

Idempotencia: provisión del mismo run valida atributos/ownership y no reinicia datos;
grants se pueden reaplicar; fixtures usan ON CONFLICT DO NOTHING y comparan valores,
sin UPDATE. Una ejecución tras eventos se rechaza. Flyway conserva su historia.
Interrumpir ante cualquier error: no continuar a la siguiente fase, no borrar
volúmenes/objetos ni reparar permisos automáticamente. Mantener evidencia privada
y revisar con el owner; una operación de recuperación exige nueva aprobación.

## Fixtures mínimos y consistencia

| Schema | Estado mínimo inicial |
|---|---|
| match_state | Un match_catalog e2e-5c84548cf006, home/away, revision=0, rules_version=1; seis tablas restantes vacías |
| dashboard | Mismo match y equipos; nombres Atlético Local / Deportivo Visitante; 0–0/CLEAR, last_event_id/updated_at nulos; inbox vacío; stream_epoch singleton UUID de V2 |
| notification | cursor_state singleton UUID de V1, last_sequence=0; inbox/alerts vacíos |
| archiver | archived_event e input_receipt vacíos; V2 aplicada, identidad por match_id/event_id |
| betting_control | authorization_authority singleton UUID de V2; demás tablas vacías, incluidas authorization_lease y market_state |
| betting_worker | Tablas vacías, incluida operation_authorization; sin ACCEPTED ni autorizaciones |

No se necesitan mercados OPEN, usuarios de apuestas, leases, historial sintético ni
filas Worker para readiness pasiva. No copiar estados de otro run. Los UUID se
crean por las migraciones originales; nunca regenerarlos en un reintento de fixtures.
Actualizar los nombres aquí requiere revisar también la configuración efectiva.

Preflight SELECT-only comprueba objetos, grants SELECT, catálogos concordantes,
singletons, baseline sin eventos, versiones Flyway, asociación y posición de las
secuencias dashboard.inbox_seq_seq y archiver.archived_event_seq_seq. No llama a
nextval/setval ni a funciones que bloqueen o autoricen. El baseline falla después
de tráfico: no sirve como test de recuperación de un partido en curso. Para ese
caso conservar epochs, IDs, cursores y revisiones y preparar otro procedimiento.

## Inventario de privilegios

Cada schema tiene `<prefix>_<servicio>_owner` NOLOGIN, migrator LOGIN NOINHERIT
miembro solo de su owner y runtime LOGIN NOINHERIT sin membresías. Prefix es
`dev3_it_5c84548cf006`; servicios: match_state, dashboard, notification, archive,
control y worker. Además un database_owner NOLOGIN. Son 19 roles, ninguno con
superuser, createdb, createrole, replication o bypassrls. No se comparten passwords.

- DBA: provisión y grants explícitos; nunca credencial de aplicación.
- Migrator: CONNECT, SET ROLE a su owner, DDL únicamente del schema propio. Sin
  contraseña runtime y sin habilitar Flyway automático de las aplicaciones.
- Runtime pasivo: CONNECT, USAGE de schema y SELECT de tablas enumeradas; sin
  permisos sobre historia Flyway, DDL, secuencias, autoridad o escrituras.
- Worker owner: USAGE betting_control y EXECUTE lock_market_authority para la
  función SECURITY DEFINER de Worker. Esto no se concede a worker_app.

Permisos **futuros separados** de 60, todavía NO aplicables:

| Runtime | DML propuesto |
|---|---|
| Match State | INSERT accepted_event/outbox/identity/quarantine/receipt; UPDATE catálogo/outbox/identity/receipt. Sin bootstrap de catálogo ni replay_attempt: replay administrativo necesita revisión aparte |
| Dashboard | INSERT inbox; UPDATE solo scores, var_status, last_event_id, updated_at; USAGE inbox_seq_seq. Bootstrap/nombres permanecen fuera del runtime mínimo |
| Notification | INSERT inbox/alerts; UPDATE solo last_sequence en cursor_state; sin secuencias |
| Archiver | INSERT archived_event/input_receipt; UPDATE input_receipt; USAGE archived_event_seq_seq |
| Control | INSERT/UPDATE de custodia/proyección/outbox según archivo; authorization_authority y authorization_lease solo SELECT |
| Worker | INSERT/UPDATE de custodia/operación/evidencia según archivo; operation_authorization solo SELECT |

No DELETE/TRUNCATE, no escritura de leases, no EXECUTE runtime de funciones de
autoridad. `BETTING_WORKER_AUTHORIZATION_MODE=disabled` y aceptación=false continúan
obligatorios. No se pretende habilitar modo postgres-atomic con estos permisos.

## Rabbit Notification

Identidad propuesta `notification_e2e_5c84548cf006`, password privado pendiente, sin
tags administrator/management/monitoring. No es el rol PostgreSQL notification_app.
El overlay integrado todavía declara mvp: este JSON propone el cambio de identidad,
NO lo aplica. Las conexiones consumidor/publicador deben usar el usuario aprobado.

Con Q=notification.dev3_it_5c84548cf006, dentro del broker/vhost exclusivo:

| Recurso | Configure | Read | Write |
|---|---|---|---|
| live_alerts (fanout durable) | Sí | Sí | No |
| Q (durable) | Sí | Sí | Sí |
| Q.dlx (direct durable) | Sí | Sí | Sí |
| Q.dlq (durable) | Sí | No | Sí |

Read exchange + write queue son necesarios para bindings. Fanout live_alerts y
Direct DLX no usan topic permissions; el binding del fanout no filtra routing keys.
El consumidor valida el allowlist de partidos. Direct DLX no tiene topic ACL;
write permite publicar en esa DLX, no limita el routing key. Binding DLQ: rejected.
RabbitAdmin explícito requiere configure incluso con recursos precreados y no
queda apagado por spring.rabbitmq.dynamic=false al habilitar messaging. Mientras
NOTIFICATION_MESSAGING_ENABLED=false, esa configuración no se crea.
Configure también permite operaciones destructivas sobre esos recursos: estas ACL
no distinguen declarar de eliminar. El operador debe aceptar ese riesgo acotado al
run. Eliminar configure del runtime requeriría cambiar el uso del RabbitAdmin y
aprovisionar externamente; no se propone modificar ese comportamiento aquí.

No conceder permisos `.*`, tags globales ni consumo de DLQ a Notification. El
responsable de infraestructura administra la DLQ; DEV 2 clasifica el fallo. Una
identidad operativa distinta puede tener read de Q.dlq y permisos de observación
aprobados. Extraer mensajes puede consumirlos/reencolarlos: no es consulta pasiva.
Replay/purga requiere permiso aparte; no se generan comandos para estas operaciones.

Validación real pendiente: declares/bindings exactos, confirms correlacionados,
mandatory/returns antes del ACK original, reencolado + detención si DLQ falla,
redelivery idempotente y persistencia. La ventana transferencia confirmada/ACK
perdido puede duplicar entradas DLQ; no hay garantía exactamente una vez.

## Readiness, aprobaciones y límites

Tras una futura autorización de arranque OFF, distinguir liveness de readiness:
Notification, Archiver, Control y Worker pueden estar vivos y OUT_OF_SERVICE por
consumidores/storage OFF. No habilitarlos para obtener verde. Dashboard puede
arrancar con catálogo válido y no detectar epoch/inbox incompletos: preflight es
obligatorio. Match State OFF omite la validación de historia; tampoco prueba datos.
El primer score de Dashboard puede ser 404 si inbox está vacío; snapshot STOMP
available=false. Es distinto de un error por esquema/epoch inexistente.

Antes de aplicar se requiere aprobación de:

1. Operador/DBA: instancias nuevas, ausencia de recursos compartidos, puertos,
   system_identifier, ownership, roles y credenciales privadas, huellas de paquete.
2. DEV 1: migraciones/datos Match State, orden fuente y replay/restauración,
   configuración OFF, identidad Kafka y compatibilidad del harness common-v1.
3. DEV 2: schemas Dashboard/Notification, catálogos, nombres, épocas, cursores,
   identidad Rabbit y custodia. Migración Notification autorizada por infraestructura.
4. DEV 3: Archiver V1/V2, Control antes de Worker, grants cross-owner y baseline
   sin leases/aceptación. Ningún cambio en su generador standalone.
5. Equipo: revisión independiente SQL/ACL, retención, operador de DLQ y procedimiento
   de detención. Arranque, tráfico sintético y E2E son autorizaciones posteriores.

Las pruebas son estructurales y con fixtures de aprobación ficticia: NO prueban
sintaxis en PostgreSQL, grants efectivos, Flyway, Rabbit, restauración ni red.
No certifican un entorno ni E2E real. No se instaló ninguna dependencia.

Validación local de la propuesta: 25 pruebas unittest offline, render determinista
con --check, inventario contrastado contra las catorce migraciones y comprobación de
whitespace. Los resultados corresponden al generador y artefactos; no a servicios.

## Cambio LiveAlert fanout (propuesta no aplicada)

Este paquete incorpora las migraciones propuestas Match State V4 y Notification V2.
Se requiere coordinaci?n DEV 1 para el store transaccional y la outbox de alertas.
No hay binding entre exchanges: Match State publica LiveAlert expl?citamente a
live_alerts mediante su outbox independiente. live_updates/ScoreUpdate V1 no cambian.
El schema AMQP LiveAlert no es el DTO REST; incluye snapshot inmutable aprobado.
La documentaci?n antigua de LiveAlert REST no sustituye el nuevo contrato wire.

A?adir a la configuraci?n OFF revisada, sin habilitarlos:
MATCH_STATE_ALERTS_ENABLED=false, MATCH_STATE_ALERTS_PUBLICATION_ENABLED=false,
NOTIFICATION_LIVE_ALERTS_ENABLED=false, NOTIFICATION_MESSAGING_ENABLED=false.
Para una futura activaci?n, el operador debe aprobar y resolver todos esos controles,
las migraciones y los permisos; no basta cambiar el nombre de un exchange.

Runtime Match State requiere INSERT/SELECT/UPDATE en alert_outbox; Notification
requiere INSERT/SELECT en live_alert_inbox adem?s de los grants existentes.
Publisher Match State: write sobre live_alerts; configure solo si declara el fanout.
Notification: configure/read live_alerts; configure/read/write su cola, configure/read/write
su DLX y configure/write su DLQ. Sin write en live_alerts y sin read de DLQ.
No recibe permisos sobre live_updates para este flujo. No se conceden tags globales.

Las alertas previas no se backfillean. Activar en un run nuevo aprobado: si existe
inbox legacy para el mismo evento, el nuevo consumidor rechaza la colisi?n y no
crea otra alerta. No se resuelve identidad tras restauraciones con un ID inventado.
Los epochs existentes no se rotan en esta propuesta. La pol?tica de restauraci?n
conjunto de outboxes e inboxes sigue pendiente del operador y DEV 1.

Los nombres anteriores de tablas vac?as se ampl?an con alert_outbox y live_alert_inbox;
stream_epoch y cursor_state siguen cre?ndose exclusivamente por las migraciones
originales, sin reseteos. La recuperaci?n LiveAlert la hace un dispatcher separado;
no adelanta ni modifica el published de score_outbox.

## Integración local académica

El inventario integrado tiene 17 migraciones: añade Dashboard V3 y las V3 académicas de Control/Worker.
`70-academic-dml.proposed.sql` es una propuesta separada, no ejecutada: sports + tablas académicas, sin
escritura de operaciones, grants, leases ni funciones de autoridad. No aplicar el fichero legacy 60 en ese ensayo.
El render solo produce texto y hashes; aprobarlo no autoriza migraciones ni DML.
