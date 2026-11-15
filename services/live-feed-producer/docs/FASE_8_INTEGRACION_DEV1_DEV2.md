# Fase 8 — Integración real DEV 1 + DEV 2

## 1. Objetivo y alcance

Validar el recorrido REST → Kafka → estado del partido → RabbitMQ → dashboard → React con brokers reales y aplicaciones en procesos independientes. Resultado observado: **siete solicitudes HTTP exitosas, siete registros Kafka, cinco mensajes RabbitMQ y cinco actualizaciones deportivas STOMP; marcador final 1–0 y VAR CLEAR**.

Ejecución: 14 de noviembre de 2026, America/Bogota. Los eventos se observaron entre las 22:19:35 y 22:19:39 locales; sus timestamps UTC corresponden al 15 de noviembre. Rama: `integration/Juan-Gabriel-validation`. Base Git: `de7a076c7ff89c00854909491ad0786c94cbfe69`, más los siete archivos autorizados del productor sin commit. Este documento se añadió después de ejecutar la prueba.

Este cierre documenta resultados anteriores; no implica una nueva ejecución ni aprobación para commit o merge.

## 2. Arquitectura de integración

```text
POST /api/integration/events
  → MatchSimulationController.publish()
  → MatchSimulationService.publish()
  → MatchEventPublisher
  → KafkaMatchEventPublisher.publish()
  → Kafka match_events [key = matchId]
  → KafkaMatchEventListener.onMessage()
  → ProcessAndPublishMatchEvent.process()
  → MatchStateService / MatchState / InMemoryMatchStateStore
  → RabbitScoreUpdatePublisher.publish()
  → RabbitMQ live_updates [topic; score.match-001]
      ├─ dashboard.score-updates [binding score.#]
      │   → ScoreUpdateListener / ScoreUpdateMessageDecoder
      │   → InMemoryDashboardProjection
      │       ├─ GET /api/matches/{matchId}/score
      │       └─ WebSocketScorePublisher → /topic/matches/{matchId}/score
      │                                  → React / STOMP.js
      └─ cola observadora propia [binding score.match-001]

Recuperación: SUBSCRIBE /app/matches/{matchId}/snapshot
Errores dashboard: dashboard.score-updates.dlx → dashboard.score-updates.dlq
```

La cola observadora recibió su propia copia y no compitió con el dashboard. El productor reutiliza su puerto y adaptador Kafka existentes; el dominio no incorpora dependencias Spring. El HTTP 204 confirma Kafka, no la entrega a RabbitMQ ni al navegador; estas últimas se observaron por separado.

## 3. Servicios ejecutados

| Servicio | PID del host | Puerto | Observación al terminar la ejecución |
|---|---:|---|---|
| dashboard-backend | 22740 | 8081 | Salud UP; conexión AMQP real; consumo y publicación STOMP |
| match-state-service | 1396 | Sin HTTP | Un partido registrado; siete registros Kafka procesados; cinco publicaciones Rabbit confirmadas |
| live-feed-producer | 2420 | 8080 | Salud UP; perfil dev; siete publicaciones Kafka confirmadas |
| dashboard-frontend | 22748 | 5173 | Vite sirviendo React; navegador Edge conectado |

Los cuatro PID corresponden a procesos independientes. Los PID internos Docker fueron 374 para Kafka y 1627 para RabbitMQ. Son datos históricos, no identificadores que deban reutilizarse ni confirmación del estado actual del host.

Herramientas registradas: Java Temurin 17.0.20.1, Maven 3.10.0, Node 26.12.1 y npm 12.0.2. Se utilizó Edge headless mediante Playwright; la versión del navegador no quedó registrada y no se presume.

## 4. Kafka y RabbitMQ

| Elemento | Configuración observada |
|---|---|
| Kafka | Imagen `apache/kafka:3.9.1`; contenedor `fase8-20261115031706-kafka` |
| Kafka host | `127.0.0.1:19092` → listener EXTERNAL interno 9094 |
| Kafka interno | Listener INTERNAL `127.0.0.1:9092`; controller 9093 |
| Topic | `match_events`, una partición, réplica 1 |
| Grupo del servicio | `match-state-service` |
| RabbitMQ | Imagen `rabbitmq:4.1.0`; contenedor funcional `fase8-20261115031706-rabbit2` |
| Rabbit host | AMQP `127.0.0.1:15673`; administración `127.0.0.1:15674` |
| Almacenamiento Rabbit | tmpfs aislado en `/var/lib/rabbitmq`, usuario 999:999 |
| Exchange | `live_updates`, tipo topic, durable, sin auto-delete |
| Routing | `score.match-001` |
| Cola dashboard | `dashboard.score-updates`, durable; binding `score.#` |
| DLX / DLQ | `dashboard.score-updates.dlx` (direct), clave `rejected`, cola `dashboard.score-updates.dlq` |

Se generaron credenciales temporales en memoria, suministradas por entorno. No se incluyen sus valores en este documento ni en el material preparado para Git. Docker conserva las variables de entorno del contenedor: no se deben publicar salidas completas de `docker inspect`.

Los puertos propuestos estaban sin listener antes de crear recursos. El puerto 5672 estaba ocupado y se preservó. Los nombres se comprobaron antes de crearlos. No se modificó Docker Compose ni se eliminaron contenedores o volúmenes existentes.

## 5. Entrada REST del productor

`POST /api/integration/events`, cuerpo camelCase. Detalles en [integration-rest.md](integration-rest.md).

- Requiere perfil activo `dev` o `test` **y** `LIVE_FEED_INTEGRATION_REST_ENABLED=true`.
- Está deshabilitado por defecto. No tiene autenticación; usar loopback y no habilitarlo públicamente.
- Conserva `eventId` y el valor temporal de `timestamp`; admite `referenceEventId`.
- Valida identificadores, tipo, minuto 0..130, timestamp y campos condicionales del dominio.
- Devuelve 204 después de la confirmación Kafka; 400 para solicitudes inválidas; 503 por fallo de publicación.
- Una repetición exacta vuelve a publicar el evento. La deduplicación del marcador corresponde al consumidor.

El adaptador Kafka conserva el formato snake_case; el contrato REST no cambia el contrato de mensajería. Un fallo HTTP o timeout no prueba que Kafka haya rechazado el evento: se debe reintentar con la misma identidad.

## 6. Pruebas automatizadas anteriores al escenario

| Componente | Pruebas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|
| live-feed-producer | 72 | 0 | 0 | 0 |
| match-state-service | 73 | 0 | 0 | 0 |
| dashboard-backend | 84 | 0 | 0 | 0 |
| dashboard-frontend | 50 | 0 | 0 | 0 |
| **Total** | **279** | **0** | **0** | **0** |

Se ejecutó `mvn -f services/<servicio>/pom.xml verify` para cada servicio Java, con un repositorio Maven alternativo externo al checkout. Los tres terminaron en `BUILD SUCCESS`. Se ejecutaron `npm ci --prefix services/dashboard-frontend` y `npm test --prefix services/dashboard-frontend`; Vitest aprobó siete archivos y cincuenta pruebas.

Las 279 corresponden a esas suites; no se suman los siete envíos reales ni la reconexión como pruebas JUnit/Vitest. Los logs y XML Surefire respaldan el recuento. Durante este cierre documental no se repitieron pruebas.

## 7. Siete eventos reales

Prefijo único: `fase8-20261115031706-`. IDs: `g1`, `g2`, `v1`, `a1`, `c1`. Los siete cuerpos conservaron timestamp `2026-11-15T03:19:35.622Z`; los duplicados conservaron también todos los demás campos. G1 pertenece a `team-home`; G2 a `team-away`. V1 y A1 referencian G2.

| Paso | Evento | Offset Kafka | HTTP | REST y Edge | VAR | Rabbit nuevos | STOMP nuevos | Revisión |
|---|---|---:|---:|---|---|---:|---:|---:|
| 1 | GOAL local G1 | 0 | 204 | 1–0 | CLEAR | 1 | 1 | 1 |
| 2 | Duplicado exacto G1 | 1 | 204 | 1–0 | CLEAR | 0 | 0 | 1 |
| 3 | GOAL visitante G2 | 2 | 204 | 1–1 | CLEAR | 1 | 1 | 2 |
| 4 | VAR_CHECK V1 → G2 | 3 | 204 | 1–1 | IN_PROGRESS | 1 | 1 | 3 |
| 5 | GOAL_ANNULLED A1 → G2 | 4 | 204 | 1–0 | CLEAR | 1 | 1 | 4 |
| 6 | Duplicado exacto A1 | 5 | 204 | 1–0 | CLEAR | 0 | 0 | 4 |
| 7 | MATCH_CONTINUES C1 | 6 | 204 | 1–0 | CLEAR | 1 | 1 | 5 |

Cada paso esperó su respuesta REST y el estado del DOM. Los eventos nuevos esperaron además el mensaje Rabbit y el frame STOMP; los duplicados no generaron actualizaciones en la ventana observada. Los totales se corroboraron con logs y lectura posterior de Kafka.

## 8. Evidencias Kafka, RabbitMQ y STOMP

Kafka registró siete mensajes en partición 0, offsets 0..6, clave `match-001`. El productor emitió siete confirmaciones y estado registró siete procesamientos completados. El grupo terminó con `CURRENT-OFFSET=7`, `LOG-END-OFFSET=7`, `LAG=0`.

Rabbit confirmó cinco publicaciones; una cola observadora recibió cinco mensajes con `content_type=application/json`, codificación UTF-8 y delivery mode 2. Ejemplo original de anulación:

```json
{"match_id":"match-001","home_score":1,"away_score":0,"event_id":"fase8-20261115031706-a1","event_type":"GOAL_ANNULLED","updated_at":"2026-11-15T03:19:38.468498900Z","var_status":"CLEAR","reference_event_id":"fase8-20261115031706-g2"}
```

La consulta final de colas devolvió cero mensajes listos y cero sin confirmar en dashboard, observador y DLQ; dashboard tenía un consumidor. Los cinco snapshots observados en STOMP coinciden con las revisiones 1..5 del dashboard. Las respuestas de recuperación se contabilizan aparte de las cinco actualizaciones deportivas.

Procedencia histórica: evidencias externas conservadas en `C:\Live-JVG-Integration-Temp`. Esa ubicación no es una dependencia del proyecto ni requisito permanente. Se puede conservar el paquete en otra ubicación externa; no se incorporan scripts, logs, binarios ni capturas al commit.

| Evidencia externa | Qué respalda |
|---|---|
| `informe.md`, `test-results.json`, logs Maven/Vitest y directorios `*-surefire/` | Ejecución, builds y 279 pruebas |
| `requests.json`, `event-results.json` | Cuerpos, respuestas 204 y verificaciones por paso |
| `kafka-messages.log`, `kafka-group.log` | Registros, claves, offsets y lag |
| `rabbit-messages.json`, `rabbit-topology.json`, `rabbit-queue-counts.log` | Mensajes, topología y colas finales |
| `websocket-frames.json` | Frames reales de los eventos |
| `final-rest.json`, `health-final.json`, `verified-counts.json` | Estado final y recuentos |
| `event-1.png` a `event-7.png`, `frontend-errors.json` | Capturas y ausencia de errores pageerror en el escenario |
| `reconnection.json`, `reconnection-websocket-frames.json`, `reconnect-only.log` | Reconexión separada exitosa |
| `scenario-error.json`, `scenario.log` | Intento offline que agotó el plazo |
| `source-hashes.json`, `artifact-hashes.json`, `run.json`, `processes.json` | Trazabilidad de fuentes, artefactos y procesos |

Las evidencias externas no se presumen disponibles en un clon nuevo. Este documento conserva los resultados esenciales y un ejemplo de payload; revisar las fuentes externas originales cuando se requiera el detalle completo.

## 9. Resultado REST final

Antes del primer evento, `GET /api/matches/match-001/score` devolvió 404. Al final devolvió HTTP 200 y `Cache-Control: no-store`:

```json
{
  "stream_id": "9293d9fb-ddce-4bb3-91e0-23077b361bc1",
  "revision": 5,
  "match_id": "match-001",
  "home_score": 1,
  "away_score": 0,
  "event_id": "fase8-20261115031706-c1",
  "event_type": "MATCH_CONTINUES",
  "updated_at": "2026-11-15T03:19:39.338546700Z",
  "var_status": "CLEAR",
  "reference_event_id": null
}
```

## 10. Frontend en navegador real

Edge headless abrió React desde Vite, sin interceptar REST ni simular el servidor WebSocket. Se observaron marcadores, revisión VAR y anulación mediante el DOM real y se guardaron capturas por evento. La captura del paso 5 también fue inspeccionada visualmente: 1–0 y «Gol anulado por VAR». Se registraron cero eventos `pageerror` durante el escenario deportivo; esto no certifica ausencia de cualquier advertencia del navegador.

El transporte real fue WebSocket nativo `/ws`, con STOMP.js y las suscripciones `/topic/matches/match-001/score` y `/app/matches/match-001/snapshot`.

## 11. Reconexión

El primer intento usó `context.setOffline(true)`. No cerró la conexión WebSocket existente y agotó 25 segundos esperando «Reconectando». El ejecutor original terminó con código 1 por esa espera; **no se presenta como una ejecución íntegramente exitosa**. Los siete eventos anteriores sí quedaron comprobados.

Una comprobación separada cerró el WebSocket nativo del navegador. La instrumentación solo conservó referencias a sockets reales, sin simular el servidor ni el transporte. Se observó «Reconectando», reconexión automática y respuesta `available=true` con el mismo `stream_id`, revisión 5, evento C1, marcador 1–0 y VAR CLEAR. El registro final contó tres sockets creados; no se presupone exactamente un único intento de reapertura.

Se conservaron capturas antes, durante y después de la desconexión. La comprobación separada terminó con código 0 y `REAL_RECONNECT_PASS`. No se reenviaron eventos ni se reiniciaron aplicaciones para esta comprobación.

## 12. Incidencias y soluciones

| Incidencia | Tratamiento y alcance |
|---|---|
| Restricciones de filesystem y Docker | Se obtuvo autorización adicional para trabajar fuera del checkout y acceder a Docker. |
| Repositorio Maven alternativo | Se copió la caché existente a la carpeta externa; no se borró ni reemplazó la original. |
| Primer RabbitMQ: `.erlang.cookie` con eacces | Se preservó el contenedor fallido y se creó otro con tmpfs aislado y usuario 999:999. No se modificó la imagen ni Compose. |
| PowerShell trató una salida diagnóstica como error fatal | Se reanudó la preparación antes de enviar eventos, reutilizando el Kafka propio. |
| Aviso npm sobre scripts esbuild | No impidió las cincuenta pruebas frontend ni servir la aplicación real mediante Vite. |
| Método offline agotó el plazo | Se conservó su evidencia y se verificó la reconexión por cierre real del socket en una comprobación separada. |

Al terminar aquella ejecución se conservaron servicios, brokers, cola observadora y contenedor fallido. Este cierre no volvió a consultar su salud ni modificó su ciclo de vida.

## 13. Limitaciones conocidas

- Estado, outbox y proyección son volátiles. Esta ejecución no demuestra recuperación tras reiniciar servicios ni exactly-once ante caídas.
- No se probaron carga, varias particiones, réplicas, fallos de brokers o notificaciones.
- Los schemas compartidos siguen vacíos: se observó compatibilidad JSON real, no conformidad con un contrato compartido aprobado.
- La proyección del dashboard conserva el límite existente de 10.000 eventos.
- La recuperación entrega el último snapshot, no reconstruye todo el historial perdido por el cliente.
- RabbitMQ en tmpfs pierde los datos de la prueba al detener el contenedor.
- La entrada REST carece de autenticación y debe permanecer deshabilitada fuera de desarrollo/pruebas locales.
- Los ejecutores del escenario y sus dependencias del navegador permanecen externos; no existe una suite E2E equivalente incorporada al repositorio por este cambio.

## 14. Conclusiones

La entrada REST permite recorrer los adaptadores reales de DEV 1 y DEV 2 en procesos separados. Los dos duplicados llegaron a Kafka sin modificar nuevamente marcador, revisión ni salida Rabbit/STOMP. Se observó la corrección VAR y el resultado final 1–0/CLEAR en REST y React. La reconexión automática y recuperación del último snapshot quedaron comprobadas por separado.

Estos resultados justifican revisar el cambio del productor y esta documentación. No amplían las garantías de persistencia o escalado ni autorizan por sí mismos un despliegue de producción.

## 15. Reproducción

Las siguientes son instrucciones para una ejecución futura autorizada; no se ejecutaron al redactar este cierre. Trabajar con recursos propios y nombres nuevos; comprobar puertos y colisiones antes de crear nada. No borrar ni reiniciar recursos ajenos.

1. Elegir un directorio externo de evidencias, repositorio Maven externo y nombres Docker únicos. Configurar sus rutas mediante variables locales; no depender de la ubicación histórica anterior.
2. Verificar Java 17, Maven, Node compatible con `package.json`, Docker y un navegador compatible con Playwright. Ejecutar `verify` por servicio y `npm ci` / `npm test` del frontend, registrando resultados antes de enviar eventos.

   ```powershell
   # MAVEN_REPO debe apuntar a una ubicación escribible fuera del checkout.
   mvn "-Dmaven.repo.local=$env:MAVEN_REPO" -f services/live-feed-producer/pom.xml verify
   mvn "-Dmaven.repo.local=$env:MAVEN_REPO" -f services/match-state-service/pom.xml verify
   mvn "-Dmaven.repo.local=$env:MAVEN_REPO" -f services/dashboard-backend/pom.xml verify
   npm.cmd ci --prefix services/dashboard-frontend
   npm.cmd test --prefix services/dashboard-frontend
   ```

3. Provisionar Kafka 3.9.1 en KRaft, un nodo broker/controller, listener INTERNAL 9092, EXTERNAL 9094, controller 9093. Publicar solo `127.0.0.1:19092:9094`; anunciar INTERNAL en `127.0.0.1:9092` para herramientas del contenedor y EXTERNAL en `127.0.0.1:19092` para clientes del host. Usar réplica 1 para offsets/transacciones y crear `match_events` con una partición y réplica 1. Esta configuración describe un entorno local, no alta disponibilidad.
4. Provisionar RabbitMQ 4.1.0 propio, publicando solo loopback 15673→5672 y, si se usa observación HTTP, 15674→15672 con management habilitado. Generar usuario y contraseña temporal en memoria y pasarlos por entorno; no incluir valores en comandos versionados ni guardar un inspect completo. Para reproducir la alternativa usada, montar tmpfs en `/var/lib/rabbitmq` con uid/gid 999 y modo 0700, ejecutando con 999:999. Comprobar readiness real de ambos brokers.
5. Configurar por proceso las variables comunes siguientes. Las credenciales se suministran externamente, sin escribirlas en el checkout ni en los informes:

   ```text
   KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:19092
   MATCH_EVENTS_TOPIC=match_events
   RABBITMQ_HOST=127.0.0.1
   RABBITMQ_PORT=15673
   RABBITMQ_EXCHANGE=live_updates
   SERVER_ADDRESS=127.0.0.1
   ```

6. Arrancar **dashboard-backend** con `DASHBOARD_PORT=8081`, listener Rabbit habilitado y `DASHBOARD_ALLOWED_ORIGINS=http://127.0.0.1:5173,http://localhost:5173`. Desde la raíz, ejecutar su JAR `services/dashboard-backend/target/dashboard-backend-0.0.1-SNAPSHOT.jar` en una terminal/proceso propio. Esperar `/actuator/health` UP y verificar la topología descrita en la sección 4. Crear una cola observadora propia enlazada a `score.match-001`; no leer la cola dashboard para observar eventos.
7. Arrancar **match-state-service** en otro proceso con su JAR y `MATCH_ID=match-001`, `HOME_TEAM_ID=team-home`, `AWAY_TEAM_ID=team-away`, `KAFKA_LISTENER_AUTO_STARTUP=true`. Comprobar un partido registrado y asignación Kafka. No tiene endpoint de salud HTTP. Usar un broker/topic limpio y dedicado evita mezclar offsets y estado de otras ejecuciones.
8. Arrancar **live-feed-producer** en otro proceso con su JAR, `SPRING_PROFILES_ACTIVE=dev`, `LIVE_FEED_INTEGRATION_REST_ENABLED=true` y `SERVER_PORT=8080`. Esperar salud UP; conservar su binding loopback. No propagar esas dos variables de habilitación a despliegues públicos.
9. Arrancar **dashboard-frontend** desde su directorio con `VITE_WS_URL=ws://127.0.0.1:8081/ws`, `VITE_MATCH_IDS=match-001` y `npm.cmd run dev`. Abrir `http://127.0.0.1:5173` en un navegador real, esperar conexión STOMP y snapshot inicialmente no disponible. Registrar PID y logs de cada proceso.
10. Crear los cinco eventos de la sección 7 con un prefijo nuevo por ejecución. Enviar siete POST siguiendo la tabla, reutilizando exactamente los cuerpos G1 y A1 para sus duplicados. Ejemplo de primer cuerpo:

    ```json
    {"eventId":"<prefijo-nuevo>-g1","matchId":"match-001","eventType":"GOAL","teamId":"team-home","minute":10,"timestamp":"2026-11-15T03:19:35.622Z","referenceEventId":null}
    ```

    Para una ejecución nueva elegir y conservar su timestamp explícito. Usar `Content-Type: application/json`; esperar HTTP 204 y cada efecto antes del siguiente paso. G2 usa el equipo visitante; V1 y A1 referencian su ID; C1 no necesita equipo ni referencia.
11. Capturar Kafka con un consumidor observador de grupo distinto, desde el inicio del topic dedicado; registrar clave, partición y offset. Observar Rabbit por cola propia y guardar cuerpos, routing y propiedades sin credenciales. Comparar después de cada paso `GET http://127.0.0.1:8081/api/matches/match-001/score`, frames STOMP y DOM/capturas reales. Esperar siete registros, cinco publicaciones, cinco actualizaciones y revisión final 5 en una proyección nueva.
12. Cerrar el WebSocket real del navegador mediante instrumentación de prueba externa, manteniendo servicios y brokers activos. Observar el estado de reconexión, nueva conexión y recuperación del snapshot. No sustituir el servidor por un mock ni asumir que el modo offline cierra sockets existentes. Guardar por separado intentos fallidos y exitosos.
13. Consolidar evidencias y hashes fuera del repositorio, revisar Git y el contenido antes de proponer un commit. Cualquier limpieza posterior debe limitarse a los recursos propios identificados y autorizarse por separado.
