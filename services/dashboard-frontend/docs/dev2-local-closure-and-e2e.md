# DEV 2 — Cierre local y preparación E2E

Fecha: 2026-11-16, America/Bogota. Este informe distingue revisión estática,
pruebas con dobles y pruebas de infraestructura. No autoriza ejecutar E2E.

## Git y alcance

- Rama verificada: `feature/Gabo-react-integration`.
- HEAD inicial: `77c219f76ac3919abf9c039882d0eb11f23c2312`.
- Estado inicial limpio. Los archivos anteriormente pendientes ya están incluidos
  en `74f74d5`, integrado mediante el PR #16 en `77c219f` por acciones previas a
  esta revisión. No se realizó ninguna operación de integración Git aquí.
- Desde la base conocida `3e89b862b2572922d00b21f7d9f2142cf28c9c5a` cambiaron
  únicamente 17 archivos del frontend. Dashboard Backend, Notification Backend,
  Match State y productor no difieren entre esa base y el HEAD inspeccionado.
- Este cierre solo añade este documento y su enlace desde README del frontend.
  No se modificaron comportamientos ni se añadieron pruebas redundantes.

## Resultados locales de esta ejecución

| Verificación | Resultado observado | Evidencia local |
| --- | --- | --- |
| Vitest completo | 135 aprobadas, 14 archivos, cero fallos | `vitest.log` |
| TypeScript/Vite | Build correcto, 67 módulos | `build.log` |
| Playwright con Chrome instalado | 14 aprobadas, código 0 | `playwright.log`, `browser/.last-run.json` |
| JavaScript del dashboard estático | 23 aprobadas con dobles, cero fallos | `dashboard-js.log` |
| Maven / toolchain | `mvn -version` termina en 1: JAVA_HOME no definido correctamente | `maven-toolchain.log` |
| Notification Maven verify | Bloqueado por ausencia de JDK verificable; no ejecutado | Revisión de herramientas |
| `git diff --check` | Sin errores | Comprobación al cierre |

Las evidencias nuevas están en
`services/dashboard-frontend/test-results/closure-20261116-155052/`, ignorado por
Git. Se preservaron los resultados anteriores. La salida de PowerShell puede
marcar avisos de stderr como NativeCommandError; el resultado del build y de
Playwright se verificó por su código de salida y el resumen del runner.

Chrome ejecutó siete escenarios en 1440×1000 y 390×844: carga/lista, vacío,
503/reintento, paginación/409, cambio de partido, demo y memory. Se comprobaron
textos extensos, orden, deduplicación, HTML como texto, fecha, Tab/Enter,
regiones y estados accesibles, ausencia de errores JavaScript y desbordamiento
horizontal. Se inspeccionaron las capturas de escritorio y móvil.

REST y STOMP fueron interceptados con fixtures locales. Solo Vite temporal usó
su propio puerto local/HMR. El cierre automático volvió a quedar esperando en
Windows; se detuvo exclusivamente el PID 26772 identificado como Vite de esta
ejecución y el runner concluyó correctamente. No se reutilizó un backend real.
Una revisión visual básica no equivale a certificación WCAG completa.

## React revisado

`notificationApi.ts`, `useNotificationAlerts.ts`, `NotificationAlerts.tsx`,
`types/alert.ts` y `LiveDashboardPage.tsx` conservan el diseño anterior:

- Origen de notificaciones HTTP(S) explícito, sin credenciales ni rutas, y fetch
  con `credentials: omit`. Los cuerpos internos de errores no llegan a la UI.
- Validación de DTO, partido, fechas de calendario, enums y límites de contenido.
- Peticiones secuenciales, cursor opaco, pausa de 250 ms durante has_more y polling
  de 5 s después de cada respuesta completa. Timeout de 10 s y sin setInterval.
- AbortController y guarda de vigencia al cambiar partido/origen, desactivar o
  desmontar. La cobertura incluye A-B-A y transportes que ignoran cancelación.
- Identidad visual UUID sin distinguir mayúsculas; el cursor solo avanza con
  páginas válidas. Se rechazan ciclos de cursor durante el drenaje.
- 409 incremental recupera desde la página inicial y reemplaza la época solo
  al recibir contenido válido. Hay cooldown de 30 s para evitar bucles.
- 400/409 repetido, 503, red o DTO inválido detienen polling automático y permiten
  reintento manual. Los fallos transitorios conservan el checkpoint confirmado.
- Alertas habilitadas solo con catálogo durable, un partido seleccionado y
  `VITE_NOTIFICATION_ORIGIN` válido. Demo y selección manual/memory no consultan
  notificaciones. No se cambia el marcador a partir de alertas.

No se encontró una brecha nueva que justificase cambiar código o ampliar las
135 pruebas existentes. La corrección de fechas imposibles ya está integrada.

## Notification Backend: revisión estática

- Spring Boot 4.1.2 y Java 17 en POM. ScoreUpdate admite los ocho campos reales
  de Match State: `match_id`, `home_score`, `away_score`, `event_id`, `event_type`,
  `updated_at`, `var_status`, `reference_event_id`. No aporta equipos, jugador o
  minuto. El codec rechaza claves desconocidas/duplicadas y JSON residual.
- GOAL, VAR_CHECK y GOAL_ANNULLED generan alerta; MATCH_CONTINUES registra inbox
  con resultado SUPPRESSED, sin alerta visible.
- `JdbcNotificationStore.accept` usa REQUIRES_NEW: inbox y alerta en la misma
  transacción. Su retorno ocurre después del commit. El listener hace ACK después
  de ese retorno; un fallo de ACK deja que inbox resuelva el redelivery.
- Identidad `(source, match_id, event_id)` con comparación del contenido. Una
  identidad con payload distinto produce conflicto; no se sobrescribe.
- Contador transaccional bloqueado en `cursor_state` para evitar que un cursor
  avance por delante de una alerta todavía no confirmada. UUID de alerta único.
- Cola propia `notification.score-updates`, independiente de
  `mvp-dashboard-scores`; exchange topic durable `live_updates` y bindings exactos
  `score.{matchId}` para IDs explícitos autorizados.
- ACK manual, prefetch 1, un consumidor. Tres intentos por defecto con pausas
  acotadas; política distingue transitorios, permanentes y desconocidos.
- DLX directa `<queue>.dlx`, DLQ durable `<queue>.dlq`, routing `rejected`.
  Transferencia persistente/mandatory y confirmación correlacionada sin returned
  antes del ACK original. Si no se confirma, NACK con requeue y parada del
  consumidor, sin declarar éxito.
- Readiness distingue inicio, almacenamiento habilitado, conexión PostgreSQL,
  consumidor habilitado/registrado/operativo y recepción de consume-ok. No declara
  READY con dependencias obligatorias deshabilitadas.
- `GET /api/alerts?match_id=...&limit=50` y `after=...` devuelven
  `items`, `next_cursor`, `has_more`. Cursor asociado a partido y época;
  consulta inválida 400, época/checkpoint obsoleto 409 y almacenamiento no disponible
  503. Orden por secuencia confirmada, no timestamp. La carga inicial es una
  ventana reciente; no ofrece navegación hacia el pasado.

Estas garantías se constataron en código, no con PostgreSQL/RabbitMQ real ni
mediante una nueva ejecución Java. Tampoco prueban el comportamiento físico de
ACK, COMMIT o reinicio en esta sesión.

`NotificationPostgresIT` y `NotificationRabbitIT` están etiquetadas como
`external-integration`. Surefire excluye esa etiqueta en verify normal; Failsafe
solo se configura al seleccionar `notification-integration`. Sus BeforeAll llaman
a IntegrationGate antes de abrir recursos. Las guardas exigen flag explícito,
configuración completa, nombres exclusivos por ejecución, credenciales privadas,
manifiesto vigente aprobado por DEV 1 e infraestructura, hashes de fuentes/JAR/
migración y propiedad de contenedores/puertos/volúmenes/redes. Rechazan overrides
heredados NOTIFICATION_, SPRING_, DB_, RABBITMQ_, PG, DOCKER_ y opciones Java.
No se invocaron estas suites ni se probaron sus recursos externamente.

## Dashboard y ProducerGateway: compatibilidad estática

Siguen presentes REST `/api/matches`, `/api/matches/{id}` y
`/api/matches/{id}/score`; conexión nativa `/ws`; destino de actualizaciones
`/topic/matches/{id}/score`; recuperación STOMP mediante suscripción
`/app/matches/{id}/snapshot`. No existe WebSocket de notificaciones.

El snapshot de dashboard añade `stream_id` y `revision` al ScoreUpdate. Su
identidad durable procede de `dashboard.stream_epoch` y partido; revision procede
de la secuencia del inbox. Duplicados no generan otra revisión; payload distinto
bajo la misma identidad se rechaza. REST y STOMP leen el snapshot comprometido;
la publicación se hace después de la transacción. No se alteraron esos contratos.

ProducerGateway conserva el POST `/api/matches/{id}/events` hacia el productor,
reenviando el cuerpo sin fabricar otro event_id ni timestamp. El productor vigente
usa ese endpoint y SNAKE_CASE. El gateway configura connect/read timeout de 2/5 s
por defecto y rechaza cero, negativos, submilisegundos u overflow. No introduce
reintentos automáticos: errores de transporte devuelven 503 incierto y advierten
conservar la identidad; las respuestas HTTP conservan su status y sustituyen el
cuerpo por texto controlado. Sus pruebas de identidad y timeout existen, pero
no se ejecutaron por falta de JDK. La cobertura JavaScript local de operaciones
pendientes pasó sus 23 pruebas con dobles.

No se demostró incompatibilidad nueva de campos o destinos. Esta conclusión es
estática y no certifica interoperabilidad entre procesos. La referencia de gol
anulado sigue siendo requerida por el consumidor: DEV 1 debe preservar esa
semántica y la identidad del payload al restaurar/republicar.

## Bloqueo Java local

`where.exe java` y `Get-Command java -All` no localizaron ejecutable. Tampoco se
encontró instalación en Program Files/Java, Eclipse Adoptium o Microsoft.
JAVA_HOME no estaba configurado y la ubicación temporal del Temurin 17 de fases
previas ya no existe. Hay Maven 3.9.16 en el cache del wrapper, pero su comando
`-version` falla sin Java. No se instaló ni descargó nada ni se modificaron PATH,
JAVA_HOME o variables globales. El usuario confirmó que no tiene otra ruta.

Una vez identificado un JDK autorizado, comprobar directamente su `bin/java.exe`
y acordar cómo proporcionarlo al proceso Maven sin alterar configuración global.
La validación Java queda pendiente; no reutilizar resultados históricos como
pruebas aprobadas en este HEAD. Con toolchain disponible, el comando local previsto
es verify offline del POM de notification, desactivando perfil externo y todos los
interruptores de infraestructura:

```text
mvn -o -B -ntp -f services/notification-backend/pom.xml -P!notification-integration -Dnotification.storage.enabled=false -Dnotification.messaging.enabled=false -Dspring.flyway.enabled=false -Dspring.rabbitmq.dynamic=false -DskipITs=true verify
```

No descargar artefactos si el cache es insuficiente. El comando anterior no se
ejecutó en esta revisión por el bloqueo de JDK.

## Requisitos E2E pendientes: no ejecutar todavía

| Requisito | Responsable / aprobación | Evidencia de aceptación futura |
| --- | --- | --- |
| Harness aislado vigente | DEV 1 y responsable de infraestructura | Proyecto, contexto Docker, IDs y etiquetas verificables, red interna y volúmenes exclusivos |
| Compatibilidad con guardas 9F | DEV 1 coordina con DEV 2 | Manifiesto compatible con IntegrationGate; tests/9f no existe en este checkout, no asumir su disponibilidad |
| Recursos PostgreSQL 17/RabbitMQ | Infraestructura con DEV 3 | Base/usuario/esquema y vhost/usuario exclusivos, permisos mínimos y credenciales por canal privado |
| Puertos | Infraestructura con DEV 3; DEV 2 informa necesidades | Loopback, sin colisiones; REST dashboard, REST notification y frontend explícitos; 8082 también es default del archiver, no asignarlo sin coordinación |
| CORS | DEV 2 configura sus servicios tras acuerdo de orígenes | REST de ambos servicios y STOMP aceptan el origen exacto del navegador; localhost y 127.0.0.1 son orígenes distintos |
| Flyway de notification | Autorización explícita de infraestructura/DEV 3 | V1 revisada, hash aprobado, base exclusiva; migración explícita separada, aplicación con Flyway deshabilitado |
| Cola independiente | DEV 2 diseña, infraestructura autoriza, DEV 1 coordina origen | Exchange/routing aprobados, cola/DLX/DLQ propias, cero consumo competidor con dashboard |
| ScoreUpdate / LiveAlert | DEV 1 coordina contratos con el equipo y DEV 2 | Acordar campos/tipos/versionado; ambos JSON Schema compartidos actuales están vacíos (0 bytes) |
| IDs, restauración y replay | DEV 1 define con DEV 2 | Dataset y match IDs acordados; conservar payload de redelivery; política explícita frente a reutilizar event_id tras restaurar |
| Retención y alcance de partidos | Equipo e infraestructura | Ventana REST, política de inbox/alertas y bindings autorizados documentados |
| Artefactos y manifiesto | Cada desarrollador en su servicio; aprobación DEV 1/infra | Fuentes, JAR y migración con hashes vigentes, acciones enumeradas y caducidad |
| Activación | Aprobación conjunta posterior | Activar almacenamiento/consumo solo cuando migración y recursos estén preparados |

Variables del frontend: `VITE_DASHBOARD_ORIGIN` (o override `VITE_WS_URL` que
determina también su REST) y `VITE_NOTIFICATION_ORIGIN`, sin secretos. Alertas
necesitan catálogo durable válido y partido seleccionado. CORS notification se
configura con `NOTIFICATION_ALLOWED_ORIGINS`; sus `NOTIFICATION_PORT`,
`NOTIFICATION_DB_*`, `NOTIFICATION_RABBIT_*`, `NOTIFICATION_EXCHANGE`,
`NOTIFICATION_QUEUE` y `NOTIFICATION_MATCH_IDS` requieren valores autorizados.
Las IT usan exclusivamente `JVG_NOTIFICATION_IT_*` según su documentación y
rechazan herencia NOTIFICATION_*. No compartir esas variables entre entornos.

Orden propuesto, sujeto a aprobación: resolver toolchain y verify local; aprobar
contratos/identidad/recursos/manifiesto; preparar recursos aislados mediante el
harness de DEV 1; autorizar migración; ejecutar suites PostgreSQL y Rabbit por
separado; iniciar componentes del flujo conjunto; comprobar navegador con alertas
reales; registrar y cerrar solo recursos propios según el plan aprobado.

| Prueba real futura | Criterio de éxito | Evidencia requerida |
| --- | --- | --- |
| Flyway, unique, rollback, concurrencia | Tablas esperadas, una alerta por ID, cero escrituras parciales | Reportes NotificationPostgresIT, consultas de inbox/alertas/contador |
| GOAL, VAR_CHECK, GOAL_ANNULLED | Una alerta visible por evento y marcador autoritativo correcto | IDs, estado SQL, GET /api/alerts y captura React |
| MATCH_CONTINUES | Inbox SUPPRESSED y ninguna alerta visible nueva | SQL y consulta incremental |
| Duplicado / ID con contenido distinto | No duplicación; conflicto no sobrescribe estado | IDs, conteos y DLQ/política de conflicto |
| ACK y redelivery | ACK después de commit; redelivery idempotente | Observaciones transaccionales y resultados NotificationRabbitIT |
| Mensaje inválido / fallo DLQ | Transferencia confirmada o original retenido y consumidor detenido | Confirm/return, ACK/NACK, conteos y readiness |
| PostgreSQL no disponible | Rollback y reintento acotado, sin ACK de trabajo no confirmado | SQL, logs y conteos de mensajes |
| REST/polling | Páginas ordenadas sin saltos ni duplicados, 409 recupera época | Cursores opacos, respuestas REST y UI |
| Dashboard y notification | Cada cola recibe copia independiente | Bindings y conteos por cola |
| Reinicio / restauración aprobados | Alertas persistidas; marcador recuperado; identidad coherente | IDs antes/después, stream_id/revision, readiness y capturas |
| Reconexión / página abierta tarde | Snapshot e incremental recuperados sin recarga manual | STOMP/REST y estado final React |
| Aislamiento | Ninguna escritura ni limpieza fuera del ámbito autorizado | Manifiesto, labels, hashes y revisión de recursos |

Estas filas están pendientes, no aprobadas. No desconectar infraestructura
compartida para provocar fallos. Cada prueba de reinicio, indisponibilidad o
migración requiere que su acción esté autorizada en el entorno exclusivo.

## Preparación para PR

El PR #16 ya contiene la implementación anterior; no duplicar ese diff en un PR
nuevo. Propuesta para los dos archivos documentales de este cierre, tras revisión
y autorización de commit/push por el usuario:

- Título: `docs(dev2): document local closure and joint E2E prerequisites`.
- Destino: dev desde la rama de trabajo acordada; sin operaciones Git automáticas.
- Descripción: registrar 135 Vitest, build, 14 Playwright simuladas y 23 JS;
  declarar Java bloqueado; documentar revisión estática de notificaciones,
  Dashboard/ProducerGateway y acuerdos pendientes de infraestructura/identidad.
- Antes de declarar aceptación completa: resolver JDK y ejecutar verify local;
  la integración real necesita una autorización posterior y evidencia independiente.

No se hicieron commits, push, pull, merges, migraciones ni pruebas externas.
