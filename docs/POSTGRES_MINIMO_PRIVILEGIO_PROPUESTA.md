# M08 — Separación de migraciones y ejecución (propuesta, no aplicada)

Compose crea actualmente live_jvg mediante POSTGRES_USER y comparte ese usuario
entre estado y dashboard. En la imagen oficial, ese usuario inicial tiene
privilegios de bootstrap, no es un usuario runtime mínimo. Las aplicaciones
ejecutan Flyway con su datasource si no se suministra configuración separada.
Separar schemas dashboard y match_state no elimina los privilegios compartidos.
No se ha consultado un servidor real: se audita la configuración del repositorio.

No se alteran Compose, roles, objetos, grants ni bases reales en esta fase. El
plan necesita revisión/aprobación de DEV 3 antes de convertirlo en provisionamiento.
Los servicios pendientes de DEV 3 permanecen intactos.

## Roles propuestos

| Rol | Función | Privilegios que no debe tener |
|---|---|---|
| dashboard_migrator | Propietario de schema/objetos dashboard y Flyway de ese schema | SUPERUSER, CREATEROLE, REPLICATION, acceso a match_state |
| match_state_migrator | Propietario de schema/objetos match_state y su Flyway | SUPERUSER, CREATEROLE, REPLICATION, acceso a dashboard |
| dashboard_runtime | DML mínimo del dashboard | DDL, ownership, membresía en migradores, escritura de época/historial previo |
| match_state_runtime | DML mínimo de estado/outbox/custodia | DDL, ownership, membresía en migradores, modificación de payloads aceptados |
| dashboard_restore_operator | Rotación administrativa controlada de stream_epoch | Uso en el proceso web, permiso general de borrar historia |

Bootstrap de esquemas por administrador: permite que los migradores no requieran
CREATE sobre toda la base. Todos requieren CONNECT y USAGE solo sobre su schema;
runtime no requiere CREATE en schema ni base. No usar el propietario de la base
como runtime ni dar privilegios globales a todos los objetos futuros.

## Matriz DML derivada de SQL actual

| Runtime | Objeto | Permiso mínimo observado |
|---|---|---|
| dashboard | matches | SELECT, INSERT, UPDATE(home_name,away_name,home_score,away_score,var_status,last_event_id,updated_at) |
| dashboard | inbox | SELECT, INSERT |
| dashboard | inbox_seq | USAGE (nextval al insertar) |
| dashboard | stream_epoch | SELECT únicamente |
| estado | match_catalog | SELECT, INSERT, UPDATE(revision) |
| estado | accepted_event | SELECT, INSERT |
| estado | score_outbox | SELECT, INSERT, UPDATE(published) |
| estado | event_identity | SELECT, INSERT, UPDATE(status,case_id) |
| estado | quarantine_case | SELECT, INSERT, UPDATE(review_state,resolved_at) |
| estado | input_receipt | SELECT, INSERT, UPDATE(delivery_attempts,last_seen) |
| estado | replay_attempt | SELECT, INSERT |

Los nombres de estado corresponden al schema match_state; los del dashboard al
schema dashboard. No se conceden DELETE, TRUNCATE, TRIGGER, REFERENCES o MAINTAIN
al runtime. accepted_event, inbox y payloads outbox quedan append-only para esas
cuentas. El runtime no necesita leer ni escribir flyway_schema_history. Los
locks FOR UPDATE de las tablas operativas requieren SELECT y UPDATE de al menos
una columna; los permisos de la matriz los cubren. La sesión outbox usa funciones
advisory normales, no un rol administrador.

## Ejemplo para revisión, no script de aplicación

Fragmento ilustrativo sobre objetos ya migrados y cuentas previamente creadas:

```sql
GRANT USAGE ON SCHEMA dashboard TO dashboard_runtime;
GRANT SELECT, INSERT ON dashboard.matches, dashboard.inbox TO dashboard_runtime;
GRANT UPDATE (home_name,away_name,home_score,away_score,var_status,last_event_id,updated_at)
    ON dashboard.matches TO dashboard_runtime;
GRANT USAGE ON SEQUENCE dashboard.inbox_seq TO dashboard_runtime;
GRANT SELECT ON dashboard.stream_epoch TO dashboard_runtime;

GRANT USAGE ON SCHEMA match_state TO match_state_runtime;
GRANT SELECT, INSERT ON match_state.match_catalog, match_state.accepted_event,
    match_state.score_outbox, match_state.event_identity, match_state.quarantine_case,
    match_state.input_receipt, match_state.replay_attempt TO match_state_runtime;
GRANT UPDATE (revision) ON match_state.match_catalog TO match_state_runtime;
GRANT UPDATE (published) ON match_state.score_outbox TO match_state_runtime;
GRANT UPDATE (status,case_id) ON match_state.event_identity TO match_state_runtime;
GRANT UPDATE (review_state,resolved_at) ON match_state.quarantine_case TO match_state_runtime;
GRANT UPDATE (delivery_attempts,last_seen) ON match_state.input_receipt TO match_state_runtime;

GRANT USAGE ON SCHEMA dashboard TO dashboard_restore_operator;
GRANT SELECT, UPDATE (epoch) ON dashboard.stream_epoch TO dashboard_restore_operator;
```

GRANT solo añade: no elimina permisos heredados, ownership o membresías previas.
Para aplicar mínimo privilegio de verdad se necesitan cuentas nuevas sin permisos
amplios y una revisión de PUBLIC/roles. Revocar a un rol no cancela permisos que
recibe desde PUBLIC. Cambios globales sobre public, TEMP/CREATE o funciones requieren
auditoría de todos los usuarios y aprobación independiente; no emitir REVOKE
global indiscriminado. Las futuras migraciones deben actualizar grants por objeto
y comprobarlos, no conceder ALL TABLES/ALL SEQUENCES a ciegas.

## Configuración de ejecución y migración

Propuesta de despliegue para después de aprobar/provisionar:

- Migraciones como paso administrativo separado, con su cuenta migradora y
  versión de scripts exacta. Conservar validación/checksums y clean deshabilitado.
- Runtime dashboard: DB_USERNAME/DB_PASSWORD del rol dashboard_runtime.
- Runtime estado: MATCH_STATE_DB_USERNAME/MATCH_STATE_DB_PASSWORD del rol
  match_state_runtime. Las URLs y el resto de ajustes del MVP no cambian por esto.
- Después de migrar y verificar esquemas, arrancar runtime con
  SPRING_FLYWAY_ENABLED=false. No dar credenciales de migración al proceso web.
  Spring admite SPRING_FLYWAY_USER/PASSWORD si se decide migración en arranque,
  pero eso mantiene credenciales privilegiadas dentro de la JVM y no es la
  separación operativa recomendada.
- El entorno de pruebas de integración usa variables TEST y una base desechable;
  no confundir su rol propietario con los roles runtime ni reutilizarlo en MVP.

No se cambia el default actual del MVP ni se desactiva Flyway automáticamente:
sin provisionamiento previo eso rompería nuevas instalaciones. No se añaden
secretos ni se publican contraseñas en CLI, archivos versionados o logs.

## Validación pendiente sobre entorno autorizado

Consultar roles/ownership/ACL, membresías y has_table_privilege /
has_column_privilege con cada identidad. Comprobar positivamente registro,
recepción, CAS, publicación, replay aprobado y lectura de época. Comprobar que
runtime no puede DROP/ALTER/TRUNCATE/DELETE, cambiar payload ni rotar época.
Simular migración/rollback compatible sin ampliar permisos del runtime.
Los tests actuales con usuario propietario no prueban estos límites. No se
declara M08 aplicado al servidor ni certificado mediante pruebas reales.

Fuentes: [GRANT PostgreSQL 17](https://www.postgresql.org/docs/17/sql-grant.html),
[propiedades Flyway de Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/index.html).
