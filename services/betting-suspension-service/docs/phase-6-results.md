# Informe final — DEV 3, Fase 6

Fecha: 2026-11-16, America/Bogota. Código preparado e inactivo; atomicidad PostgreSQL real **NO certificada**.

## 1. Estado y validación local

| Servicio | Exitosas ejecutadas | Omitidas | Fallos | Errores |
|---|---:|---:|---:|---:|
| Historical Archiver | 68 | 1 | 0 | 0 |
| Betting Suspension Service | 93 | 1 | 0 | 0 |
| Betting Worker | 86 | 12 | 0 | 0 |
| Total | **247** | **14** | **0** | **0** |

261 casos descubiertos. Las 14 omisiones son pruebas PostgreSQL pendientes, incluidas las 11 de AtomicPostgresConcurrencyTest. No hay omisiones ocultadas como éxitos. Maven test y package de cada módulo finalizaron BUILD SUCCESS; package repite la suite, no se suman esas ejecuciones. Los JAR quedan empaquetados.

Se usa Java 17/Boot 3.5.7 y caché temporal escribible offline, sin cambiar POM/dependencias de esta fase ni borrar caché/detener procesos. Evidencia por módulo: docs/evidence/phase-6-local-verification.txt.

## 2. Decisión sobre atomicidad

Los defaults usan bases separadas, por lo que hoy **no hay autoridad transaccional común desplegada**. No se hicieron consultas a roles/owners reales. Se implementa el modo condicionado postgres-atomic para una DB común aprobada con esquemas separados y funciones delimitadas.

La función de origen toma el mismo FOR UPDATE que Suspension, usando la conexión/transacción del worker. La autorización queda ligada a contenido/identidad, base, backend y transacción; se guarda antes de ACCEPTED y ambos tienen constraints diferidos que exigen confirmación conjunta y vigencia al comprobar commit. La sesión Java no puede usarse fuera de su recurso transaccional.

Suspension usa una transacción separada, serializada por ese lock, no una transacción distribuida compartida mágicamente. La garantía se limita a suspensiones **confirmadas** antes del lock y al orden de commits. No cubre VAR todavía en tránsito. Sin base común o permisos correctos, no usar REST como simulación: se mantiene BLOCKED.

## 3. Cambios implementados

- Control V2: epoch, leases cortas con reloj DB y función SECURITY DEFINER de lock; emisor interno sin bean/endpoint/scheduler.
- Worker V2: autorización, función de registro y restricciones diferidas en ambos sentidos: sin grant no ACCEPTED, y sin ACCEPTED no se confirma grant. Rechaza upgrades de ACCEPTED V1 sin revisión explícita.
- Worker: modo disabled predeterminado, verificación de DB/epoch, permisos y vínculo a DataSource transaccional; la lectura OPEN ya no basta aunque la antigua elegibilidad se configure true.
- Orden de locks y READ COMMITTED/timeout de 5 s en configuración; rollback completo ante conflictos y reintentos a nivel de entrega.
- Pruebas unitarias de protocolo, permiso antiguo, sesiones, límites/rollback/leases y guard estable; preparación de 11 casos PostgreSQL con dos conexiones y rol dedicado.
- Propuestas finales de contratos y documentación actualizada, sin tocar contracts/events ni servicios ajenos.

Inventario exacto por módulo: docs/phase-6-files.txt. V1 y migraciones previas se conservaron; no se borró ningún archivo anterior.

## 4. Qué prueban y qué no prueban los tests

Las nuevas comprobaciones locales validan flujo/grant/commit, bloqueo del antiguo permiso por lectura, recurso transaccional real de Spring sobre conexión simulada, errores, versiones, roles devueltos por mock y leases. **No validan la ejecución de PL/pgSQL, constraints, ACL, locks o caída/reinicio de PostgreSQL.** No se ejecutó SQL real.

Los casos preparados incluyen ambos órdenes de suspensión/aceptación, operaciones concurrentes, UNKNOWN y expiración, fuente inaccesible, reinicio lógico/versiones, deadlock 40P01 y nueva transacción, rollback después de grant, expiración antes del commit, ACCEPTED sin grant y grant sin aceptación. Requieren las aprobaciones/variables/DB nueva descritas en el diseño. No se ejecutaron por estar desactivados.

## 5. Contratos pendientes

Propuesta final: contracts/final-contract-proposal.md, dos schemas en contracts/ de este módulo y schema de comando en betting-worker/docs/contracts. Son propuestas locales, no contratos aprobados. Identidad match_id/event_id; cierre VAR explícito con actor/version; notificaciones de suspensión no equivalen a permiso; comandos simulados sin finanzas; compatibilidad/versionado coordinados.

Los campos nuevos del permiso atómico son datos internos de DB y no se agregaron al mensaje deportivo ni a ScoreUpdate. No se conectó automáticamente el outbox provisional a la aceptación del worker.

## 6. PostgreSQL real, Kafka/Rabbit y activaciones

Requisitos completos, orden de migración, owners/grants, modo y epoch, garantía y límites: [phase-6-atomic-security.md](phase-6-atomic-security.md).

Se necesita PostgreSQL 17 y una DB común real para control/worker; dueños NOLOGIN y logins mínimos separados; V2 de ambos módulos; EXECUTE explícito en funciones, sin DML fuente/grants para worker; verificación de identidad/configuración común; política/caller de lease con verificación de fuente aprobada. Archiver puede tener otra DB y mantiene su V1/V2.

Kafka/Rabbit requieren recursos y credenciales dedicados, grupos distintos y contratos/topologías aprobados. No se crearon recursos ni se cambiaron Compose/contratos. Consumidores, publicación, aceptación, modo atómico y Flyway permanecen apagados por defecto. Health/profiles/variables de integración siguen preparados.

## 7. Riesgos y bloqueos

1. SQL/roles/aislamiento/atomicidad no certificados sin tests reales; no declarar producción lista ni activar solo por mocks.
2. Lease issuer no está conectado y su checkpoint es attestation, no prueba implementada de catch-up. Falta aprobar/construir el caller/liveness; no hay leases reales.
3. TTL usa reloj DB y vigencia en el control diferido antes del commit; exige relojes disciplinados y no promete instante físico de VAR/commit final.
4. Modo común necesita que **Suspension y Worker** apunten a la autoridad real, no a una copia/otra DB. Epoch/owners se revisan en DBA; rotación exige drenar admisiones y reconciliar guards.
5. Migraciones nuevas y cualquier dato ACCEPTED V1 requieren revisión antes de aplicar. No se inventa evidencia histórica.
6. Las pruebas de brokers/reentregas/reinicio físico siguen pendientes. El caso de permiso inaccesible no equivale a detener/reiniciar PostgreSQL.

## 8. Git y protección

Rama `integration/Valeria-mvp-dev` mantenida. HEAD inicial 9d8aae2; actual `f6b86829ab023f7e101045623e5008991193f48c` por avance externo (merge de frontend/notificaciones). No se ejecutaron commits, pull, merges, checkout, reset, clean, stash, rebase o push por este agente.

No hay cambios de ese avance dentro de los tres módulos. Se compararon 452 hashes externos iniciales: 11 diferencias coinciden con archivos comprometidos en el HEAD actual; no se sobreescribieron. Los demás permanecen idénticos. Los cambios previos DEV 3 se conservaron y solo se editaron archivos del alcance.

Respaldo previo verificado: /private/tmp/live-jvg-dev3-phase6/modules-before.tar.gz, fuentes/documentos, sin datos DB. El árbol conserva trabajo sin commit. Revisión estática/secretos: phase-6-static-review.txt.

## 9. Próximas autorizaciones exactas

1. Aprobar contratos y semántica de correlación/cierre, vigencia, autoridad y punto de decisión.
2. Aprobar DB común, roles/owners/grants y caller/verificador que emite leases. Bases separadas requieren alternativa de autoridad única, no REST como atomicidad.
3. Autorizar migraciones revisadas y ejecución de tests PostgreSQL dedicados; revisar resultados antes de certificar.
4. Autorizar integración real Kafka/RabbitMQ aislada y pruebas de recuperación no destructivas.
5. Habilitar consumo y publicación solo después, por separado. Aceptación únicamente tras aprobar y demostrar el mecanismo real, no por una bandera.
6. Commits/PR/push solo con autorización nueva; no se realizaron.

Se completa el trabajo seguro local y se detiene aquí. No se habilita aceptación ni se declara cerrada la integración real.
