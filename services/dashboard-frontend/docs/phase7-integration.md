# Fase 7 — Informe de integración DEV 2

Fecha: 2026-11-14, America/Bogota. Base inspeccionada: `4690c3d`, rama
`feature/Gabo-dashboard`. El usuario confirmó que se esperará a DEV 3 para definir
y exponer los puertos Docker de Kafka y RabbitMQ. No se inició ni cambió Docker.

**Resultado: validación parcial aprobada; flujo completo pendiente de infraestructura.**
No se ejecutó ni se declara exitoso el recorrido productor → Kafka → estado →
RabbitMQ → dashboard → navegador con eventos reales en esta sesión.

## Versiones verificadas

| Componente de la cadena | Versión / configuración |
|---|---|
| live-feed-producer | 0.0.1-SNAPSHOT, Java 17, Spring Boot 3.5.7 |
| match-state-service | 0.0.1-SNAPSHOT, Java 17, Spring Boot 3.5.7 |
| dashboard-backend | 0.0.1-SNAPSHOT, Java 17, Spring Boot 3.5.7 |
| Herramientas Java utilizadas | Temurin 17.0.21, Maven 3.9.16 |
| Frontend | React 19.3.1, TypeScript 5.9.3, Vite 7.3.8, STOMP.js 7.5.0 |
| Herramientas frontend | Node 24.21.0, npm 12.2.0, Vitest 4.1.12, Playwright 1.65.1 |

Los otros esqueletos Java (notification-backend, historical-archiver,
betting-suspension-service y betting-worker) aún declaran Boot 4.1.2. No forman
parte de este recorrido y no se modificaron ni se validaron sus compilaciones.
Las versiones de los brokers no se pudieron consultar: Docker no estaba accesible.

## Resultados esperados y observados

| Verificación | Esperado | Observado | Alcance |
|---|---|---|---|
| Productor, clean verify | Compilar y pasar pruebas | BUILD SUCCESS, 43 pruebas | Sin broker real |
| Estado, clean verify | Compilar y pasar pruebas | BUILD SUCCESS, 73 pruebas | Sin brokers reales |
| Dashboard, clean verify | Compilar y pasar pruebas | BUILD SUCCESS, 84 pruebas | Incluye WebSocket real local |
| Frontend, Vitest | Casos de estado y contratos correctos | 50 pruebas aprobadas | Unitarias/componentes, transporte simulado |
| Frontend, build | TypeScript estricto y bundle válido | Compilación correcta, dist generado | Producción |
| Chrome, demo | Render y estados responsive | 2 pruebas aprobadas | Escritorio y móvil |
| Chrome, eventos STOMP | Gol local, visitante, VAR, anulación y duplicados | 2 pruebas aprobadas | Cliente real, servidor simulado |
| Chrome, backend real | Handshake y snapshot disponibles según estado | 2 pruebas aprobadas; available=false | Backend aislado sin Rabbit |
| Reinicio real del backend | Reconectar y volver a recuperar estado | 2 pruebas aprobadas | Procesos propios, escritorio y móvil |
| Kafka y RabbitMQ reales | Brokers disponibles y autenticados | No disponibles | Bloqueo de infraestructura |
| Cadena completa | Eventos del productor visibles en React | No ejecutada | Pendiente de DEV 3 |

Totales de las ejecuciones finales: **200 pruebas backend, 50 frontend y 8 de
navegador**, sin fallos ni errores. Se generaron los tres JAR ejecutables y el
bundle frontend. Estos resultados no equivalen a validar la cadena completa.

## Infraestructura observada

`docker-compose.yml` sigue teniendo `services: {}`. La consulta de solo lectura
a Docker, también fuera del sandbox, mostró el contexto desktop-linux y el error:

```text
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
The system cannot find the file specified.
```

Se comprobaron por TCP 127.0.0.1:9092, :19092, :5672, :15673 y :15672.
Todos devolvieron cerrado. Se repitió la comprobación fuera del sandbox para
descartar un falso bloqueo por permisos de red. No había variables de sesión
que identificaran otro Kafka/RabbitMQ autorizado. Un puerto cerrado no permite
comprobar protocolo, autenticación, exchange, colas ni garantías de persistencia.

No se iniciaron los servicios de DEV 1 contra brokers inexistentes, no se crearon
topics/colas y no se borraron recursos ni se cambiaron offsets. Tampoco se
reiniciaron contenedores, volúmenes o procesos compartidos.

## Contratos y configuración

Los cuatro archivos de `contracts/events` tienen 0 bytes: no son contratos JSON
utilizables ni aprobados. Las propuestas locales de MatchEvent y ScoreUpdate sí
se pudieron leer como JSON, pero eso no constituye validación JSON Schema completa
ni aprobación del contrato compartido.

Configuración real inspeccionada:

- Productor Kafka: bootstrap por KAFKA_BOOTSTRAP_SERVERS, predeterminado
  localhost:9092; topic por MATCH_EVENTS_TOPIC, predeterminado match_events.
  Key = matchId, StringSerializer, JSON snake_case, acks=all e idempotencia habilitada.
- Consumidor de DEV 1: grupo predeterminado match-state-service, auto-commit=false,
  ACK por registro, un consumidor. Registra partidos mediante MATCH_ID,
  HOME_TEAM_ID y AWAY_TEAM_ID o match-state.matches. Estado/outbox volátiles.
- Rabbit: exchange topic durable live_updates, routing score.{matchId}, JSON
  persistente, mandatory y publisher confirms. Sin binding compatible DEV 1
  considera fallida la publicación y reintenta Kafka.
- Dashboard: cola durable dashboard.score-updates, binding score.#, DLX/DLQ propias,
  máximo tres intentos de procesamiento y rechazo sin reencolar tras fallo definitivo.
- WebSocket nativo /ws; live /topic/matches/{matchId}/score y snapshot
  /app/matches/{matchId}/snapshot. DTO al navegador añade stream_id/revision.

El JSON Rabbit contiene match_id, home_score, away_score, event_id, event_type,
updated_at, var_status y reference_event_id. No contiene nombres de equipos,
minuto ni timestamp deportivo original. No hay catálogo consultable de partidos.

Se mantiene una diferencia de configuración a resolver: `.env.example` raíz
declara KAFKA_TOPIC_MATCH_EVENTS, pero el código lee MATCH_EVENTS_TOPIC.
Las credenciales de ejemplo no prueban autenticación y no deben asumirse válidas.

## Pruebas reales de conexión y reconexión

Se inició únicamente dashboard-backend en el puerto exclusivo 18081 con Rabbit
desactivado, usando su JAR recién compilado. Chrome completó CONNECT/CONNECTED,
la suscripción live y la consulta inicial. El servidor respondió
`SnapshotReply[available=false, snapshot=null]`; React mostró marcador no
disponible y no fabricó un 0–0. Se verificó en escritorio y móvil.

Además se añadió `e2e/backend-reconnect.spec.ts`. Antes de iniciar, exige un
puerto libre y no termina procesos preexistentes. La prueba crea su propio
dashboard vacío, conecta el navegador, detiene solo ese proceso, comprueba
Reconectando/Sin actualizar, crea una instancia nueva y verifica reconexión y
nueva respuesta de snapshot. Mantiene Rabbit desactivado y no publica eventos.
Ambos proyectos Chrome pasaron; sus procesos se retiraron en finally.

Los casos deportivos de Chrome usan el cliente STOMP.js real con el servidor
WebSocket simulado de Playwright. Verifican recepción de marcadores completos,
VAR, anulación, descarte de duplicados, mensaje inválido y reconexión del cliente.
No demuestran envío de esos goles a través de Kafka/RabbitMQ.

## Errores y correcciones

- Docker inicialmente también mostró acceso restringido a su configuración.
  La comprobación autorizada de lectura eliminó esa ambigüedad y confirmó que
  tampoco estaba disponible el pipe del daemon. No se corrigió iniciando Docker
  ni modificando infraestructura; el usuario indicó esperar a DEV 3.
- Los avisos npm de stdout/stderr aparecen en los logs PowerShell como
  NativeCommandError. Las ejecuciones terminaron con exit code 0 y sus resúmenes
  indican pruebas/compilaciones correctas; esos avisos no son fallos de pruebas.
- No se encontraron fallos de aplicación que requirieran cambiar producción.
  Los únicos cambios funcionales de esta fase son un script de pruebas y la
  prueba de reinicio aislado; no se cambió código de otros servicios.

## Evidencias

Directorio local ignorado por Git:
[dashboard-backend/target/phase7-evidence](../../dashboard-backend/target/phase7-evidence).
Se preservó fuera de los directorios eliminados por los clean y las ejecuciones
Playwright, y se copió al directorio final después de terminar las compilaciones.

- [summary.json](../../dashboard-backend/target/phase7-evidence/summary.json): resultados y bloqueo explícito.
- [environment.json](../../dashboard-backend/target/phase7-evidence/environment.json): commit, herramientas, contratos y SHA-256 de los JAR.
- [manifest.json](../../dashboard-backend/target/phase7-evidence/manifest.json): tamaño y SHA-256 de cada evidencia.
- Logs Maven: live-feed-producer-verify.log, match-state-service-verify.log,
  dashboard-backend-verify.log.
- Logs frontend: frontend-unit.log, frontend-build.log, frontend-browser.log,
  backend-reconnect.log.
- infrastructure.log y backend-smoke.log.
- browser-initial/: capturas demo/live y resultado de la primera suite Chrome.
- browser-reconnect/: logs de las instancias aisladas y capturas tras reconectar.

Un clean posterior de dashboard-backend elimina estas evidencias locales;
preservarlas antes de volver a compilar si se necesitan para revisión.

## Reproducción de los checks disponibles

Con Java 17 y Maven disponibles, desde la raíz:

```powershell
mvn -f services/live-feed-producer/pom.xml clean verify
mvn -f services/match-state-service/pom.xml clean verify
mvn -f services/dashboard-backend/pom.xml clean verify
```

En dashboard-frontend:

```powershell
npm.cmd test
npm.cmd run build
```

Para el reinicio real aislado, con Chrome local y el JAR compilado:

```powershell
$env:JAVA17_BIN = 'RUTA-AL-JDK-17/bin/java.exe'
$env:VITE_WS_URL = 'ws://127.0.0.1:18081/ws'
$env:REAL_BACKEND_RESTART = '1'
npm.cmd run test:integration
```

El test usa un worker y crea/cierra sus propios procesos. No necesita RabbitMQ.
Sin REAL_BACKEND_RESTART se omite explícitamente. El smoke de la otra suite usa
LIVE_BACKEND_SMOKE=1 y requiere una instancia vacía ya iniciada; no confundir
ambos mecanismos ni usar el mismo puerto simultáneamente.

## Pendientes con DEV 1 y DEV 3

**DEV 3:** configurar y comunicar puertos expuestos y endpoints alcanzables,
versiones y estado de brokers, credenciales por un mecanismo seguro, permisos,
virtual host si corresponde, topic, retención y autorización para recursos aislados.
Esta espera fue confirmada por el usuario. No se modifica Compose desde DEV 2.

**DEV 1:** aprobar contratos, confirmar registro de partido/equipos, semántica de
replay y orden. El productor no ofrece un endpoint de simulación implementado;
se necesita un ejecutor/prueba que invoque su MatchEventPublisher real, sin
sustituirlo por publicación manual directa a Kafka. Esa prueba completa no se
creó ni ejecutó mientras faltan brokers.

**DEV 2:** retomar la cadena cuando existan endpoints aprobados. Proponer un ID
de ejecución único, topic/grupo y cola propios, con permisos acordados. Declarar
el binding del dashboard antes del primer envío. No resetear offsets ni vaciar
colas compartidas para preparar la prueba.

Secuencia completa pendiente, resultados **esperados**, no observados en brokers:

| Envío | Evento | Marcador esperado | VAR | Nueva actualización Rabbit |
|---|---|---|---|---|
| 1 | Gol local G1 | 1–0 | CLEAR | Sí |
| 2 | G1 idéntico | 1–0 | CLEAR | No |
| 3 | Gol visitante G2 | 1–1 | CLEAR | Sí |
| 4 | VAR_CHECK de G2 | 1–1 | IN_PROGRESS | Sí |
| 5 | GOAL_ANNULLED de G2 | 1–0 | CLEAR | Sí |
| 6 | Anulación idéntica | 1–0 | CLEAR | No |
| 7 | MATCH_CONTINUES | 1–0 | CLEAR | Sí |

Se esperan siete entradas Kafka y cinco snapshots Rabbit. Correlacionar event_id,
offsets, confirms, consumo del dashboard y capturas del navegador; verificar que
duplicados no añaden eventos visibles. Probar desconexión del navegador o de sus
procesos aislados, sin detener brokers compartidos. Informar por separado cualquier
fallo, retry o DLQ y preservar evidencia antes de proponer correcciones.

Sin commits, push, cambios a contratos compartidos ni modificaciones de infraestructura.
