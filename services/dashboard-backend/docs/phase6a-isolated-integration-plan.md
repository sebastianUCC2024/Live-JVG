# Fase 6A — Plan de integración aislada (no ejecutar)

**Diseño sujeto a autorización por etapa.** No se crea infraestructura, fichero
de credenciales, manifiesto ni migración en esta fase. Dependencias y responsables:
[decisiones](phase6a-decisions-and-git.md). Resultados exigibles:
[criterios de aceptación](phase6a-acceptance-criteria.md).

## 1. Alcance y recursos

Reutilizar un laboratorio 9F existente solo si DEV 1 e infraestructura lo aprueban
y las guardas verifican su propiedad. `tests/9f` no existe en el checkout actual.
No sustituirlo por un segundo stack ni eliminar guardas para sortear el bloqueo.
No cambiar Compose principal, PostgreSQL nativo, recursos MVP o servicios de DEV 3.

| Recurso propuesto | Aislamiento / autorización necesaria |
| --- | --- |
| Proyecto/contexto Docker | Run aprobado de 12 hex; esquema de proyecto live-jvg-9f-<run>; endpoint local explícito, IDs/labels de contenedor, volúmenes y red interna verificables |
| PostgreSQL 17 | Mismo motor aislado aprobado si es compatible; bases independientes por servicio, sin acceso a bases nativas/compartidas |
| Notification DB | Guardas actuales exigen live_jvg_test_<run>_notification, usuario jvg9f_<run>_notification, esquema notification y marker de propiedad public.notification_test_owner creado por infraestructura |
| Dashboard DB | Guardas actuales exigen base live_jvg_test_<run>_* y usuario jvg9f_<run>; proponer sufijo dashboard; esquema dashboard y permisos solo sobre su base |
| RabbitMQ | Vhost Notification IT 9f_<run>_notification, usuario dedicado, exchange 9f_<run>_notification_updates, prefijo notification.9f_<run>.integration para fixtures y DLX/DLQ propias |
| Flujo conjunto Rabbit | Dashboard y Match State requieren aprobación adicional para publicar/consumir en el ámbito elegido; colas propias de dashboard y notification con el mismo binding, nunca consumidor competidor |
| Kafka | DEV 1 proporciona topic/grupos/advertised listeners aislados y compatibles con ejecución host/contenedor; key=match_id; Archiver solo si DEV 3 autoriza su grupo independiente |
| Dataset | Match IDs y equipos coordinados; fixtures de prueba con identidades exclusivas de la ejecución, sin reutilizar eventos MVP ni restaurados |

Las suites Notification crean sufijos por prueba. No usar esas colas transitorias
como colas runtime del flujo conjunto. Acordar recursos runtime diferentes antes
de arrancar servicios. Una contraseña o permiso en otro vhost no habilita este.

Puertos host: loopback, disponibles, explícitos en manifiesto. PostgreSQL/Rabbit
IT rechazan puertos nativos/MVP conocidos; comprobar las listas de IntegrationSettings
y DurableIntegrationSafety antes de asignar. Notification 8082 puede colisionar con
Archiver 8082; dashboard durable 8080, memory 8081 y frontend 5173 son defaults,
no asignaciones aprobadas. Kafka requiere que las direcciones anunciadas coincidan
con los clientes autorizados. No asumir Docker o disponibilidad de puertos actual.

## 2. Guardas, variables y migraciones

Notification IT solo acepta `JVG_NOTIFICATION_IT_*`: APPROVED, RUN_ID,
DOCKER_CONTEXT, DOCKER_ENDPOINT, DB_PORT, RABBIT_PORT, DB_URL, DB_USERNAME,
DB_PASSWORD, RABBIT_USERNAME, RABBIT_PASSWORD, RABBIT_VHOST, EXCHANGE, QUEUE_PREFIX
y APPROVAL_FILE. Los valores deben coincidir con los patrones de IntegrationSettings.
Se rechazan overrides heredados NOTIFICATION_, SPRING_, DB_, RABBITMQ_, PG*,
DOCKER_ y JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS. Usar un proceso hijo de pruebas limpio,
sin alterar variables globales. No se asignan flags de aprobación en esta fase.

El manifiesto privado debe estar bajo `.9f/<run>/`, con caducidad, aprobaciones
DEV 1/infraestructura, acciones enumeradas, fuentes/compilados/JAR/migración con
hashes, endpoint/contexto y propiedad real de contenedores/red/volúmenes/puertos.
No contiene passwords. Una aprobación anterior no cubre binarios/configuración
distintos. Las credenciales se facilitan por canal privado, sin argumentos de
shell, Git, logs, capturas ni variables VITE_. No generarlas aquí.

Dashboard IT utiliza otra interfaz: LIVE_JVG_INTEGRATION_APPROVED, AUDIT_RUN_ID,
AUDIT_PROJECT, DASHBOARD_INTEGRATION_DB_URL, DASHBOARD_TEST_DB_USERNAME y
DASHBOARD_TEST_DB_PASSWORD. Su guarda no realiza todas las comprobaciones Docker
de Notification: exigir verificación externa de propiedad igualmente, sin suponer
equivalencia de protecciones entre suites.

Notification V1 es `V1__notification_inbox_and_alerts.sql`; dashboard usa V1 de
proyección y V2 de época persistente. Revisar hashes, permisos de CREATE/schema y
history de Flyway separada. La aplicación Notification mantiene Flyway desactivado.
**Las IT PostgreSQL/Rabbit de Notification pueden ejecutar Flyway durante setup**:
aprobar `migrate` es necesario antes de activarlas, aunque se espere DB ya migrada.
La suite de época de dashboard también crea/migra su esquema de prueba.

## 3. Secuencia y responsables

| Etapa | Responsable / dependencia | Acción futura y puerta de salida |
| --- | --- | --- |
| S0 — congelar alcance | Equipo; DEV 2 coordina D01..D09 | Aprobar snapshot de contratos V1 por commit, dataset, garantías de orden/identidad y acciones; P2 queda fuera salvo decisión formal separada |
| S1 — preparar laboratorio | DEV 1 e infraestructura/DEV 3 | Verificar harness, recursos y propiedad; entregar credenciales privadas y manifiesto vigente; detenerse si no cumplen guardas |
| S2 — empaquetar y revisar DDL | Cada responsable en su servicio; infra autoriza | Toolchain local, verify sin externos, JAR/fuentes/migraciones identificados; autorizar DDL solo en bases exclusivas |
| S3 — Notification PostgreSQL | DEV 2 tras autorización DB/migrate/restart-probe | NotificationPostgresIT: V1, restricciones, atomicidad/rollback, concurrencia, nueva JVM y cursor; guardar SQL/IDs/reportes |
| S4 — Notification Rabbit | DEV 2 tras autorización declare/publish/consume/fallos acotados | NotificationRabbitIT: redelivery real, duplicados/conflictos, confirm/return/DLQ, original retenido y recuperación del consumidor |
| S5 — proyección dashboard | DEV 2 con base exclusiva aprobada | DurableSnapshotIntegrationTest y StreamEpochMigrationIntegrationTest; consistencia/ventana/época, sin servicios ajenos; reconocer migraciones del setup |
| S6 — flujo conjunto V1 | DEV 1 inicia productor/Match State; DEV 2 sus servicios; DEV 3 lo suyo | Salud/readiness, catálogo y bindings correctos; generar eventos por mecanismo real aprobado; demostrar Kafka→Rabbit→DB→REST/STOMP |
| S7 — React real | DEV 2 con URLs/CORS aprobados | VITE_DASHBOARD_ORIGIN y VITE_NOTIFICATION_ORIGIN, partido desde catálogo; gol local/visitante, VAR/anulación, alertas reales y páginas incrementales |
| S8 — fallos/reinicio | Equipo, acciones autorizadas en manifiesto | Desconexión/reconexión, apertura tardía, pérdida de ACK/respuesta, reinicio de servicio y DB/broker solo si se autorizan explícitamente |
| S9 — cerrar evidencia | DEV 2 recopila; cada dueño valida su tramo | Matriz esperado/observado/estado, hashes y pendientes; aprobar E2E solo si todas las garantías del alcance tienen evidencia real |

NotificationRabbitIT no demuestra automáticamente todos los eventos: completar
en el flujo conjunto GOAL, VAR_CHECK, GOAL_ANNULLED y supresión MATCH_CONTINUES,
copias independientes para dashboard y notification, REST servido por HTTP real
y UI real. Algunas pruebas existentes llaman al listener con broker real o al
adaptador REST por MockMvc: registrar ese alcance, sin presentarlo como aplicación
completa desplegada. El reinicio de nueva JVM tampoco equivale a reiniciar el host.

No seleccionar el perfil notification-integration ni integration-postgres hasta
autorizar sus efectos. El verify ordinario mantiene externos excluidos. Consultar
comandos existentes en [preparación de IT Notification](../../notification-backend/docs/phase3b4-isolated-tests.md),
sin interpretarlos como autorización actual. No ejecutar un comando copiado si
su configuración deriva de otro entorno o no demuestra aislamiento.

## 4. Evidencia reproducible

Por etapa guardar: commit y estado de fuentes, Java/Maven/Node, hash de JAR y DDL,
run_id/manifiesto no secreto, versiones/IDs de recursos, topic/key/routing y nombres
de colas, event_id/alert_id, revision/stream_id, secuencia/cursor, conteos SQL,
confirm/return/ACK/NACK/DLQ observados, respuestas REST saneadas, frames STOMP,
capturas React y duración. Mantener entrada exacta de fixtures para reproducción.

Cruzar prueba por identidad: publicación Kafka confirmada, aplicación Match State,
outbox Rabbit publicada, inbox dashboard/notification comprometidos, alerta REST
y estado visible. Un log de «enviado» o un puerto abierto no demuestra todos esos
pasos. No mezclar evidencia simulada, laboratorio y entornos compartidos.
No sobrescribir evidencia previa; credenciales y headers sensibles no se registran.

## 5. Detención segura y limpieza

Si un host, permiso, hash, label, puerto o manifest no coincide: no conectar ni
ejecutar setup. Si ocurre un fallo en curso: detener emisores/consumidores propios
de forma controlada, registrar estado antes de intervenir y conservar originales
sin ACK cuando corresponda. No reencolar/republicar indefinidamente ni regenerar IDs.

Cerrar procesos temporales que pertenezcan al run; no matar Node/Java globalmente.
No ejecutar down -v, borrar volúmenes, purgar colas compartidas ni limpiar por un
prefijo calculado no verificado. Las fixtures solo pueden limpiar recursos propios
después de demostrar ownership; conservar DB/esquemas y DLQ como evidencia si así
lo pide el plan. La retirada definitiva de recursos requiere aprobación separada
y lista exacta del inventario. No eliminar evidencias para dejar el entorno verde.

Un criterio crítico fallido o bloqueado impide declarar aceptación integral.
Las pruebas P2 solo se programan tras D02, versionado y aprobación; S3/S4 de la
implementación V1 pueden autorizarse por separado sin adoptar P2.
