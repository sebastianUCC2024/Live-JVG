> Configuración vigente: [modos de ejecución](../../docs/MODOS_EJECUCION.md). El dashboard durable usa 8080 en el host y 18080 publicado por Compose; memory conserva 8081. La escritura está deshabilitada por defecto; Compose la habilita explícitamente para el MVP local. REST y STOMP consultan la proyección durable. Los resultados históricos siguientes no sustituyen la validación de la rama actual.
>
> Para ejecutar la implementación original del equipo sin PostgreSQL, usa `SPRING_PROFILES_ACTIVE=memory` con los comandos de este documento. Ese perfil conserva su consumidor, DLQ y proyección en memoria; su estado es volátil. No actives este perfil en el contenedor MVP que requiere persistencia.

## Documentación original del modo memory — DEV 2

Java 17, Spring Boot 3.5.7 y Maven. Recibe snapshots de marcador por RabbitMQ,
los valida y los transmite al navegador mediante STOMP. Mantiene una proyección
local en memoria y permite consultarla para recuperar la conexión.

El texto de esta sección describe el modo memory original. El MVP durable y las
correcciones 9A–9D están implementados; la validación externa de la rama actual
sigue pendiente y no se sustituye por evidencia de fases anteriores.

## Arquitectura

```text
RabbitMQ → ScoreUpdateListener → ScoreUpdateMessageDecoder
         → ProcessScoreUpdate → ProcessScoreUpdateService
         → DashboardUpdatePublisher → InMemoryDashboardProjection
         → MatchSnapshotPublisher → WebSocketScorePublisher → STOMP

REST / consulta STOMP → GetMatchSnapshot → GetMatchSnapshotService
                     → MatchSnapshotRepository → InMemoryDashboardProjection
```

Dominio y aplicación no dependen de Spring, Jackson, AMQP ni WebSocket. Los
adaptadores están bajo infrastructure/amqp, memory, web y websocket. La composición
Spring conecta los puertos mediante inyección por constructor. La proyección
compone almacenamiento y publicación dentro de una sección sincronizada local;
no constituye una transacción distribuida ni una outbox durable.

El dashboard muestra el marcador autoritativo de DEV 1. No incrementa goles,
no vuelve a aplicar anulaciones ni decide reglas VAR.

## Contratos reales

Se revisó el publicador actual de match-state-service. Exchange topic durable
`live_updates`, routing `score.{matchId}`, JSON UTF-8 snake_case, message_id igual
al event_id original y entrega persistente. El cuerpo contiene:

```json
{
  "match_id": "match-001",
  "home_score": 1,
  "away_score": 0,
  "event_id": "event-001",
  "event_type": "GOAL",
  "updated_at": "2026-11-14T12:00:00.079218900Z",
  "var_status": "CLEAR",
  "reference_event_id": null
}
```

Tipos de evento: GOAL, VAR_CHECK, GOAL_ANNULLED, MATCH_CONTINUES. Estados VAR:
CLEAR, IN_PROGRESS. Los marcadores son enteros no negativos y los IDs no vacíos.
reference_event_id puede faltar o ser null salvo en GOAL_ANNULLED, que exige
referencia no vacía. updated_at es el instante de procesamiento de DEV 1, no el
instante deportivo original. No se publican nombres de equipos ni minuto.

Los cuatro archivos compartidos en contracts/events siguen vacíos. Las propuestas
locales son referencia provisional, no contratos compartidos aprobados. Las
pruebas usan cinco muestras documentadas por DEV 1; su procedencia está en
[src/test/resources/fixtures/README.md](src/test/resources/fixtures/README.md).

## RabbitMQ

| Recurso | Valor predeterminado |
|---|---|
| Exchange de entrada | live_updates, topic durable |
| Cola propia | dashboard.score-updates, durable |
| Binding | score.# |
| Exchange de rechazo | dashboard.score-updates.dlx, direct durable |
| Cola de rechazo | dashboard.score-updates.dlq, durable |
| Clave de rechazo | rejected |
| Consumo | Una instancia de consumidor, prefetch 1, ACK AUTO |
| Fallo de procesamiento | Tres intentos totales, intervalo fijo de 1000 ms |

El broker necesita estar accesible y el usuario tener permisos para declarar esos
recursos. No se cambian ni eliminan colas existentes para resolver argumentos
incompatibles; coordinarlo con DEV 3. La DLQ no tiene TTL ni retorno automático.

El decoder rechaza JSON inválido, claves duplicadas, propiedades desconocidas,
campos obligatorios ausentes, coerciones y fechas numéricas. Mensajes inválidos y
conflictos de identidad se rechazan sin reintentos. Otros fallos agotan hasta tres
intentos y terminan en rechazo sin reencolar; el broker intenta enviarlos a la DLQ.
El dead-lettering requiere la topología operativa y no promete ausencia de pérdidas
ante fallos del broker. No se confirmó todavía con RabbitMQ real en DEV 2.

## WebSocket, consulta y recuperación

| Función | Endpoint o destino |
|---|---|
| Conexión STOMP nativa | /ws |
| SUBSCRIBE live por partido | /topic/matches/{matchId}/score |
| SUBSCRIBE de snapshot directo a la sesión | /app/matches/{matchId}/snapshot |
| Consulta REST | GET /api/matches/{matchId}/score |
| Salud | GET /actuator/health |

El DTO del navegador conserva los campos de ScoreUpdate y añade stream_id y
revision. La respuesta inicial STOMP es `{available:true,snapshot:{...}}` o
`{available:false,snapshot:null}`. REST devuelve el snapshot con 200 o 404 cuando
no existe, con Cache-Control: no-store. Nunca se inventa un 0–0.

El navegador se suscribe primero al destino live y después al snapshot en la misma
sesión. Se preservan orden de recepción/publicación y heartbeats de 10 segundos.
El cliente compara revision dentro del mismo stream_id. La revisión es local a
esta proyección y cambia de stream al reiniciar; no ordena eventos de DEV 1.

No se admite SEND del navegador ni suscripciones wildcard. Las desconexiones
se procesan de forma idempotente. El simple broker es local, sin historial durable.
[Protocolo detallado](docs/phase4-websocket.md).

## Variables y seguridad de configuración

| Variable | Predeterminado / efecto |
|---|---|
| DASHBOARD_PORT | 8080 durable / 8081 memory; precede a PORT; SERVER_PORT conserva precedencia de Spring |
| RABBITMQ_HOST | localhost |
| RABBITMQ_PORT | 5673 durable host / 5672 memory; Compose usa 5672 interno |
| RABBITMQ_USERNAME / RABBITMQ_PASSWORD | guest / guest, solo desarrollo local |
| RABBITMQ_EXCHANGE | live_updates |
| DASHBOARD_RABBIT_LISTENER_ENABLED | true |
| DASHBOARD_ALLOWED_ORIGINS | http://localhost:5173,http://127.0.0.1:5173 |
| SPRING_PROFILES_ACTIVE | prod activa el requisito de orígenes explícitos |

La topología y reintentos se pueden sobrescribir con propiedades
`dashboard.rabbit.*`; `dashboard.projection.max-events` vale 10000 por defecto.
Spring Boot permite suministrarlas externamente. No se introdujo un cargador .env:
[.env.example](.env.example) es una plantilla, que no se carga automáticamente.
Exportar las variables en el proceso o usar la configuración externa autorizada.

El perfil prod exige DASHBOARD_ALLOWED_ORIGINS no vacío, con orígenes HTTP(S)
explícitos y sin wildcard/rutas. Esto no exige credenciales Rabbit distintas de
guest por sí solo: hay que proporcionarlas externamente antes de desplegar.
No versionar credenciales ni colocarlas en variables VITE_ del frontend.

Solo health está expuesto por Actuator y sin detalles. Los errores HTTP de entrada
usan ProblemDetail; no se publican mensajes ni stack traces de errores inesperados.
Los logs propios de fallos AMQP incluyen tipos de error, sin cuerpos ni contraseñas.
La revisión no encontró indicios de secretos reales en los archivos versionados
de los dashboards; guest y los valores REPLACE_* son marcadores de desarrollo.

No hay autenticación/autorización por usuario. CORS/orígenes y el bloqueo de SEND
no sustituyen esos controles. TLS y acceso de despliegue quedan pendientes de la
configuración acordada; no se presentan como implementados en esta entrega.

## Ejecución local

Desde la raíz, con JDK 17 y Maven disponibles:

```powershell
mvn -B -ntp -f services/dashboard-backend/pom.xml test
# Para generar JAR: package/verify en un checkout nuevo o salida aislada; no limpiar JAR en uso.
java -jar services/dashboard-backend/target/dashboard-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=memory
Invoke-RestMethod http://localhost:8081/actuator/health
```

Para uso real, proporcionar primero las variables Rabbit de DEV 3. El arranque
normal habilita listener, declaraciones y salud Rabbit. Sin broker la salud puede
indicar DOWN y el listener intentará conectarse. Compose del MVP durable limita
el dashboard a una instancia; STOMP simple sigue siendo local a la JVM.

Para inspeccionar solo REST/STOMP sin broker (no valida la cadena completa):

```powershell
java -jar services/dashboard-backend/target/dashboard-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=memory --spring.rabbitmq.listener.simple.auto-startup=false --spring.rabbitmq.dynamic=false --management.health.rabbit.enabled=false
```

Para perfil prod, configurar previamente credenciales autorizadas y ejecutar con
`--spring.profiles.active=memory,prod` para este modo o `prod` para durable,
suministrando DASHBOARD_ALLOWED_ORIGINS. No poner
contraseñas en argumentos de terminal ni en documentación versionada.

## Errores, duplicados y límites del perfil memory

- Se deduplica por (match_id,event_id), comparando scores, evento, VAR y referencia.
  Un cambio exclusivo de updated_at se ignora sin reemplazar el snapshot actual.
  Otro contenido para ese ID se rechaza. Un replay con distinto marcador necesita
  coordinación; no se resuelve alterando reglas del dominio desde el dashboard.
- Solo se guarda el estado/deduplicación después de aceptar la publicación STOMP.
  Un fallo se propaga al retry AMQP; aceptar el envío no confirma recepción final
  del navegador. Puede haber duplicados de transporte.
- Memoria, deduplicación y pendientes no son durables. Reiniciar puede dejar el
  marcador no disponible. Una cola durable no recupera mensajes ya confirmados.
- No escalar a múltiples instancias con esta proyección local y cola compartida.
- El límite de 10000 eventos es global al proceso. Al saturarse, nuevos eventos
  fallan y terminan en DLQ tras agotar intentos. No hay expulsión silenciosa de IDs.
- Se aplica orden de llegada, no reordenamiento por updated_at. No hay exactly-once.
- No existe fuente externa de snapshots ni catálogo/nombres de equipos disponible.

## Verificación y dependencias

Las pruebas incluyen modelo, casos de uso, decoder, compatibilidad con DEV 1,
topología declarada, reintentos, duplicados, errores HTTP y WebSocket real local.
Las pruebas de contexto desactivan Rabbit; no demuestran el consumo en broker.
La [revisión final DEV 2](../dashboard-frontend/docs/phase8-final-review.md) es
evidencia histórica; la validación vigente está en el [informe 9E](../../docs/FASE_9E_CONTRATOS_CALIDAD_CI.md).
Los resultados históricos permanecen en los documentos de cada fase.

Con DEV 1: aprobar contratos, IDs/equipos, orden, replay y fuente de snapshots.
Con DEV 3: puertos, credenciales seguras, permisos, topología, políticas de DLQ,
retención y garantías de recuperación. La [guía durable](../../docs/PRUEBAS_INTEGRACION_DURABLE.md)
separa las pruebas externas de la suite local. No se activan durante la auditoría.
