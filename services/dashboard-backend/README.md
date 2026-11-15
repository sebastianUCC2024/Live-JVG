# Dashboard backend — Fase 4

Java 17, Spring Boot 3.5.7 y arquitectura hexagonal. Dependencias: Spring Web,
Actuator, Spring AMQP, Spring WebSocket y Starter Test. Sin base de datos.

## WebSocket y recuperación — Fase 4

Conexión STOMP nativa en `/ws`, suscripción de actualizaciones en
`/topic/matches/{matchId}/score`, consulta inicial por SUBSCRIBE a
`/app/matches/{matchId}/snapshot` y consulta HTTP mediante
`GET /api/matches/{matchId}/score`. Véase [el protocolo y sus límites](docs/phase4-websocket.md).

La fuente es la última proyección recibida por este proceso, en memoria. No hay
consulta HTTP disponible en DEV 1 ni recuperación durable después de reiniciar.
REST responde 404 cuando no hay snapshot. STOMP responde `available: false`.
El DTO para el navegador añade stream_id y revision; el contrato Rabbit no cambia.

## Fuente de integración verificada

Se inspeccionó el código actual de DEV 1: `MessagingConfiguration`,
`RabbitScoreUpdatePublisher`, `ScoreUpdate` y `application.properties` de
match-state-service. La integración de `a096343` ya está en `origin/dev` y en el
HEAD actual (`43aab3e`). La rama local sigue siendo `feature/Gabo-dashboard`;
no se crearon ni cambiaron ramas durante esta fase. La auditoría 3A actualizó las
referencias mediante fetch. El `dev` local permanece en `85c43dc`.

El publicador declara `live_updates` como exchange topic durable y publica con
`score.{matchId}`. Envía JSON UTF-8, snake_case, persistente, sin cabeceras de tipo
Java, con message_id igual a event_id, mandatory y confirmaciones del broker.
El contrato `contracts/events/score-update.schema.json` sigue vacío (0 bytes).
La compatibilidad se basa en el código y propuesta de DEV 1, pendientes de acuerdo.

Ejemplo del cuerpo compatible:

```json
{
  "match_id": "match-001",
  "home_score": 1,
  "away_score": 0,
  "event_id": "event-001",
  "event_type": "GOAL",
  "updated_at": "2026-11-14T12:00:00Z",
  "var_status": "CLEAR",
  "reference_event_id": null
}
```

Eventos: GOAL, VAR_CHECK, GOAL_ANNULLED, MATCH_CONTINUES. Estado VAR: CLEAR o
IN_PROGRESS. Se requieren todos los campos salvo reference_event_id, que debe
ser no vacío para GOAL_ANNULLED. IDs no vacíos, marcadores enteros no negativos
y fecha textual ISO 8601 deserializable a Instant. No se infieren equipos ni se
recalculan reglas de goles/VAR: se aplica el marcador resultante de DEV 1.

El decoder rechaza JSON mal formado, claves duplicadas, propiedades desconocidas,
objetos concatenados, enum numérico, coerciones de IDs/marcadores, fechas numéricas,
campos obligatorios ausentes o nulos y referencias inválidas. El mapper es local
al adaptador; no cambia la configuración JSON de Spring MVC.

## Arquitectura

```text
RabbitMQ → ScoreUpdateListener → ScoreUpdateMessageDecoder
         → ProcessScoreUpdate → ProcessScoreUpdateService
         → DashboardUpdatePublisher → InMemoryDashboardProjection
         → MatchSnapshotPublisher → WebSocketScorePublisher → STOMP

REST / STOMP snapshot → GetMatchSnapshot → GetMatchSnapshotService
                     → MatchSnapshotRepository → InMemoryDashboardProjection
```

- `domain/model`: snapshot inmutable y validaciones, sin Spring ni AMQP.
- `domain/port/in` y `domain/port/out`: interfaces independientes del transporte.
- `application/service`: delega el snapshot y propaga fallos.
- `infrastructure/amqp`: topología, listener, decoder y política de reintentos.
- `infrastructure/memory`: último snapshot y deduplicación del proceso.
- `infrastructure/web`: errores HTTP seguros con ProblemDetail.

El caso de uso está registrado en Spring con una salida hacia la proyección y
publicación WebSocket. La consulta usa un puerto de lectura independiente.

## Configuración

`src/main/resources/application.yml` contiene:

| Configuración | Valor predeterminado / variable |
|---|---|
| Host AMQP | localhost / RABBITMQ_HOST |
| Puerto AMQP | 5672 / RABBITMQ_PORT |
| Usuario y contraseña | guest / RABBITMQ_USERNAME, RABBITMQ_PASSWORD |
| Timeout de conexión | 5s |
| Exchange de entrada | live_updates / RABBITMQ_EXCHANGE |
| Cola propia durable | dashboard.score-updates |
| Binding | score.# |
| Exchange de rechazo durable, direct | dashboard.score-updates.dlx |
| Cola de rechazo durable | dashboard.score-updates.dlq |
| Routing key de rechazo | rejected |
| Intentos máximos totales | 3 |
| Espera entre intentos | 1000 ms |
| Máximo de eventos retenidos | 10000 |
| Arranque del listener | true / DASHBOARD_RABBIT_LISTENER_ENABLED |
| HTTP | 8081 / DASHBOARD_PORT o SERVER_PORT |

Los nombres se sobrescriben con propiedades `dashboard.rabbit.*`; el límite con
`dashboard.projection.max-events`. Las colas no son exclusivas ni auto-delete.
La DLQ no tiene TTL ni binding de retorno: no hay bucle automático de reproceso.
El usuario del broker necesita permisos para declarar exchange, colas y bindings.
Si ya existe una cola con esos nombres y argumentos incompatibles, la declaración
fallará; resolver con DEV 3 sin eliminar colas existentes automáticamente.

El listener tiene un consumidor, prefetch 1 y ACK AUTO: el retorno exitoso confirma
el mensaje después de actualizar la proyección. Los errores se propagan. Los
reintentos de procesamiento son stateless y ocurren dentro de la entrega actual;
no se habilita una segunda política automática de retry de Boot.

## Duplicados, errores y límites

- Se deduplica por (match_id, event_id) en la proyección sincronizada. Se comparan
  home_score, away_score, event_type, var_status y reference_event_id. Un duplicado
  con esos mismos datos termina correctamente sin reemplazar el snapshot actual,
  incluso si cambia únicamente updated_at. DEV 1 genera esa fecha al procesar y
  un replay puede cambiarla; no forma parte de la identidad del contenido.
  Se conserva el timestamp del snapshot ya aplicado. Cambiar cualquier otro dato
  comparado produce un conflicto y rechazo, sin alterar estado. Esta decisión local
  de DEV 2 no formaliza el contrato compartido ni resuelve un replay que cambie el
  marcador resultante: ese caso necesita coordinación antes del reproceso.
- Mensaje inválido o conflicto de identidad: un intento, sin reintentos.
- Otros fallos de procesamiento: máximo tres intentos totales (dos reintentos),
  con espera fija de un segundo. Un éxito posterior confirma normalmente.
- Tras un fallo definitivo se lanza AmqpRejectAndDontRequeueException; el contenedor
  rechaza sin reencolar y el broker enruta mediante DLX hacia la DLQ configurada.
  Esta política sigue el mecanismo de [recuperación de Spring AMQP](https://docs.spring.io/spring-amqp/reference/3.2/amqp/resilience-recovering-from-errors-and-broker-failures.html).
- Los logs propios solo incluyen tipos de fallo, sin cuerpos, credenciales ni
  mensajes de excepción del parser. El original se conserva en la DLQ cuando el
  broker logra completar el dead-lettering; no se promete entrega exactamente una
  vez ni transferencia sin pérdidas ante fallos de broker. Acordar garantías y
  tipo/políticas de cola con DEV 3.
- La DLQ requiere inspección y reproceso manual después de corregir la causa. No
  hay consumidor ni republicación automática de mensajes de esa cola. Conservar
  IDs al reprocesar y coordinar el orden para no regresar snapshots.
- Los tres intentos son por entrega en este proceso: una caída o nueva entrega
  puede reiniciar la cuenta. No es un límite durable de intentos globales.
- Memoria y deduplicación se pierden al reiniciar. ACK no significa persistencia
  del snapshot. No escalar este adaptador a varias instancias: compartirían carga
  de la cola, pero no sus proyecciones ni sus registros de duplicados.
- Se aplica el orden de llegada de eventos nuevos. updated_at no es una secuencia;
  un snapshot atrasado con un ID nuevo puede reemplazar al actual. Un duplicado
  antiguo ya conocido no lo reemplaza. Resolver orden y recuperación con DEV 1.
- Al alcanzar 10000 eventos no se expulsan IDs silenciosamente: un evento nuevo
  falla, agota los intentos y se deriva a DLQ. Los duplicados conocidos siguen
  aceptándose. Definir retención y almacenamiento durable antes de producción.

## Ejecución y pruebas

Desde la raíz, con Java 17 y Maven disponibles:

```powershell
mvn -B -ntp -f services/dashboard-backend/pom.xml clean verify
java -jar services/dashboard-backend/target/dashboard-backend-0.0.1-SNAPSHOT.jar
Invoke-RestMethod http://localhost:8081/actuator/health
```

De Actuator solo se expone health, sin detalles. En ejecución normal incluye el estado del
broker: sin RabbitMQ puede indicar DOWN. Las pruebas de contexto desactivan el
arranque del listener, RabbitAdmin y el indicador de salud Rabbit; funcionan sin
broker real. No desactivan esas funciones en la configuración de producción.

Las pruebas cubren JSON válido/inválido, campos requeridos, actualizaciones, VAR,
anulaciones, duplicados y conflictos, capacidad, reinicio, propagación de errores,
retry real con firma de invocación del contenedor (Channel, Message), éxito tras
fallo, agotamiento y clasificación de errores envueltos. También verifican las
declaraciones de topología y el endpoint de salud.

Para Fase 3B se copiaron el JDK 17 y la caché a un directorio temporal fuera de
target antes de ejecutar `clean verify`. No se cambió la configuración global.
El log final se conserva en target, ignorado por Git; un clean posterior lo elimina.
Configurar JDK/caché externos para uso habitual.

### Compatibilidad verificada en Fase 3B

`src/test/resources/fixtures/dev1-score-updates.json` conserva los cinco cuerpos
RabbitMQ documentados por DEV 1 en `FASE_7_EVIDENCIAS.md`, commit `7b606b9`.
La procedencia exacta se explica en el README de fixtures. Los tests no dependen
del módulo de DEV 1 ni cargan su código en el contexto del dashboard.

`Dev1ScoreUpdateCompatibilityTest` recorre listener → decoder → caso de uso →
proyección usando esos mensajes: gol local, gol visitante, VAR_CHECK,
GOAL_ANNULLED y MATCH_CONTINUES. Comprueba IDs, referencias, marcador, estado VAR
y timestamps con precisión de nanosegundos. Reproduce la secuencia con duplicados
y verifica que un replay con solo una fecha diferente no regresa el snapshot.

Las pruebas Mockito del interceptor recorren el listener y caso de uso reales:
fallo temporal seguido de éxito, fallo persistente con tres intentos y JSON
mal formado con una sola ejecución y ninguna invocación del caso de uso.
Son pruebas sin broker, no evidencia de ACK/NACK o transferencia DLQ en RabbitMQ.

Resultado de Fase 3B (2026-11-14): **BUILD SUCCESS, 75 pruebas, 0 fallos,
0 errores y 0 omitidas**. Se ejecutó `clean verify` sobre el POM específico con
Maven 3.9.16 y Temurin 17.0.21; fuentes principales y de pruebas recompiladas con
release 17 y JAR ejecutable generado. El primer intento offline no pudo resolver
Maven Clean 3.4.1, ausente en caché; se descargó y la ejecución completa terminó
con código 0. Log: `target/phase3b-verify.log`.

Comando, usando el JDK y la caché temporal externos a target:

```powershell
mvn -B -ntp "-Dmaven.repo.local=$dashboardVerifyDir/maven-cache" -f services/dashboard-backend/pom.xml clean verify
```

`dashboardVerifyDir` identifica el directorio temporal preparado para esta
verificación; para ejecución habitual basta usar el comando clean verify anterior
con JDK 17 y la caché Maven normal. No se ejecutaron pruebas con brokers reales.

### Verificación realizada el 2026-11-14

Maven 3.9.16, Temurin 17.0.21 y el POM específico de dashboard-backend:
**BUILD SUCCESS, 63 pruebas, 0 fallos, 0 errores y 0 omitidas**. JAR ejecutable
generado. Log local: `target/phase3-verify.log`.

Tras descargar Spring AMQP, la ejecución final usó modo offline y la caché local:

```powershell
# Con JAVA_HOME apuntando al JDK 17 local y el binario Maven 3.9.16:
mvn -B -ntp -o "-Dmaven.repo.local=$PWD/services/dashboard-backend/target/maven-cache" -f services/dashboard-backend/pom.xml verify
```

La primera suite detectó que Jackson aceptaba timestamps numéricos y que la
prueba del interceptor no usaba la firma real del contenedor. Se añadió validación
textual explícita de updated_at y se corrigió la firma de la prueba; la ejecución
final pasó completa. No se ejecutaron conexiones ni pruebas con RabbitMQ real.

## Pendientes de integración

1. Aprobación del JSON por el equipo; a096343 ya está integrado en origin/dev.
2. Con DEV 3: permisos, conectividad, políticas DLQ, retención y garantías del broker.
3. Prueba con brokers: declarar topología del dashboard antes de publicar, enviar
   GOAL, VAR_CHECK y GOAL_ANNULLED, comprobar snapshots y duplicados; enviar JSON
   inválido y verificar DLQ/x-death sin bucle de reentrega. No se ha ejecutado.
4. Recuperación inicial, orden/versionado y proyección/deduplicación durables.
5. Validación frontend del protocolo de recuperación y estrategia de reconexión.

Sin cambios a otros servicios, contratos, Compose, frontend, commits ni push.
