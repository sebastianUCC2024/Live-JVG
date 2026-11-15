# Fase 4 — STOMP y recuperación del último marcador

## Conexión y destinos

- WebSocket nativo: `ws://localhost:8081/ws` (usar `wss` detrás de TLS).
- Protocolo: STOMP 1.2, sin SockJS ni plugin STOMP en RabbitMQ.
- Actualizaciones: SUBSCRIBE `/topic/matches/{matchId}/score`.
- Snapshot directo: SUBSCRIBE `/app/matches/{matchId}/snapshot`.
- Consulta HTTP: GET `/api/matches/{matchId}/score`.

El broker STOMP es el simple broker local de Spring, independiente de RabbitMQ.
Se conserva el orden de publicación y recepción por sesión. Se configuran
heartbeats de 10 segundos, negociados con el cliente. Las suscripciones se retiran
al cerrar la sesión; `WebSocketSessions` registra altas y bajas de forma idempotente,
incluidos eventos de desconexión repetidos. No registra credenciales ni payloads.

Solo se permiten suscripciones por partido y a su snapshot. Se rechazan los
comodines y cualquier SEND del navegador, evitando que un cliente publique un
marcador falso en el broker. No se añade autenticación ni control por usuario;
el alcance de esta fase es la lectura de marcadores con orígenes permitidos.
Los IDs se conservan; el destino de salida codifica el ID como segmento URI.
Para IDs actuales como `match-001`, el destino es literalmente
`/topic/matches/match-001/score`.

## Mensaje hacia el navegador

REST y las actualizaciones STOMP comparten `MatchSnapshotResponse`:

```json
{
  "stream_id": "3fd8f0b2-3158-4e67-82af-ea03f79c9444",
  "revision": 12,
  "match_id": "match-001",
  "home_score": 1,
  "away_score": 0,
  "event_id": "event-001",
  "event_type": "GOAL",
  "updated_at": "2026-11-15T00:12:12.079218900Z",
  "var_status": "CLEAR",
  "reference_event_id": null
}
```

`stream_id` identifica la proyección del proceso. `revision` aumenta globalmente
por actualización nueva aceptada, por lo que puede tener saltos para un partido.
No es una secuencia de DEV 1 ni un contador de goles. Cambia stream_id al recrear
la proyección. Los duplicados no publican ni incrementan revision. updated_at
conserva el instante recibido de DEV 1; no se usa para ordenar snapshots.
Este DTO es local a DEV 2 y no cambia los contratos compartidos.

La respuesta al SUBSCRIBE de snapshot es directa a la sesión solicitante:
`{"available":true,"snapshot":{...}}`. Si no existe estado:
`{"available":false,"snapshot":null}`. La consulta HTTP responde 200 con el DTO
o 404 sin inventar un 0–0; ambas respuestas llevan Cache-Control: no-store.

## Recuperación al conectar y reconectar

1. Abrir `/ws` y completar CONNECT/CONNECTED.
2. En esa misma sesión, enviar SUBSCRIBE al destino live del partido con un ID
   de suscripción propio. Empezar a almacenar temporalmente las actualizaciones.
3. Después enviar SUBSCRIBE a `/app/matches/{matchId}/snapshot` con otro ID.
   El servidor conserva el orden de recepción por sesión: la suscripción live
   se procesa antes de atender la consulta inicial.
4. Tomar el snapshot como base y aplicar las actualizaciones almacenadas del mismo
   stream_id que tengan revision mayor. Descartar revisiones iguales o menores.
   Si no hay snapshot, esperar una actualización; no mostrar un marcador fabricado.
5. Tras desconectar, crear una sesión nueva, renovar ambas suscripciones y repetir
   la recuperación. Si cambia stream_id, descartar la base anterior y usar la nueva.
   El frontend debe implementar reconexión con espera progresiva; el backend
   permite una sesión nueva, pero no reconecta automáticamente un navegador.

REST permite consultar el mismo estado y revalidarlo. Para el arranque del flujo
live se recomienda la consulta STOMP por la misma conexión, en vez de asumir que
una petición HTTP implica que una suscripción enviada en paralelo ya está activa.
No se depende de receipts del simple broker para esta sincronización.

## Fuente disponible y límites

Se inspeccionó match-state-service: tiene repositorio interno en memoria, pero no
endpoint REST para consultarlo. La única fuente accesible al dashboard es la
proyección local de los eventos RabbitMQ ya consumidos. `GetMatchSnapshot` y
`MatchSnapshotRepository` permiten reemplazarla después por una fuente aprobada.

- La reconexión del navegador recupera el último marcador si el proceso del
  dashboard sigue vivo y ya lo recibió.
- Reiniciar el dashboard pierde snapshots, deduplicación y revisiones. Una cola
  durable no recupera mensajes previamente confirmados. Se responderá 404/no
  disponible hasta recibir otro evento del partido.
- No se escalan estas proyecciones a varias instancias compartiendo la cola:
  cada una recibiría solo parte de los eventos y tendría su propio stream_id.
- La revisión local evita regresiones entre respuesta inicial y actualizaciones
  de este proceso. No corrige el orden de eventos atrasados o replay de DEV 1.
- Una aceptación por SimpMessagingTemplate no confirma recepción del navegador.
  El simple broker no proporciona historial durable para un navegador desconectado.

La solución mínima implementada es consulta de snapshot en memoria + recuperación
en cada conexión. Para recuperación tras reinicio, acordar con DEV 1 una consulta
autoritativa o un flujo de snapshots/replay controlado. No se introduce una base
de datos ni se cambian servicios ajenos sin una tarea aprobada.

## Publicación, fallos y validación

La entrada continúa pasando por el decoder estricto y el modelo antes de publicar.
Los mensajes inválidos se rechazan sin publicar. La proyección genera el snapshot
y llama al puerto MatchSnapshotPublisher. Solo tras aceptar esa llamada guarda
el estado y marca el evento como procesado. Si falla, propaga el error a los
reintentos RabbitMQ y no registra una deduplicación que impida el reintento.
Un reintento sin eventos intermedios conserva stream_id y revision.

Se mantienen tres intentos totales de procesamiento y el rechazo/DLQ de Fase 3.
Puede haber duplicados de transporte si una publicación se aceptó antes de un
fallo; el navegador debe usar stream_id/revision. No hay garantía exactly-once.
La construcción de un modelo válido sigue sin recalcular reglas VAR de DEV 1.

## CORS por entorno

`DASHBOARD_ALLOWED_ORIGINS` es una lista separada por comas, usada tanto en el
handshake WebSocket como en `/api/**` (solo GET). Ejemplo de desarrollo:
`http://localhost:5173,http://127.0.0.1:5173`.

Con perfil `prod`, la variable es obligatoria: la aplicación falla al arrancar
si no contiene orígenes HTTP(S) explícitos válidos. No se admite `*`, patrones,
credenciales, rutas, query ni fragmentos. Ejemplo de origen: `https://sports.example`.
Los orígenes son un control del navegador; no sustituyen autorización.

## Verificación realizada

Java 17.0.21, Spring Boot 3.5.7, Maven 3.9.16:

```powershell
mvn -f services/dashboard-backend/pom.xml clean verify
```

La ejecución usó además `-B -ntp -o` y una caché Maven externa a target.
Resultado: **BUILD SUCCESS, 84 pruebas, 0 fallos, 0 errores, 0 omitidas**;
JAR ejecutable generado. Log local: `target/phase4-verify.log`.

Las pruebas nuevas comprueban transformación/destino, rechazo de mensajes inválidos,
fallo de publicación seguido de reintento sin duplicar, orígenes y ciclo de sesiones.
La prueba de integración inicia Tomcat en un puerto aleatorio y usa el cliente
WebSocket real del JDK para negociar STOMP, suscribirse a dos partidos, verificar
aislamiento, cerrar, reconectar, recuperar mediante REST y STOMP y recibir el
siguiente evento. Verifica también 404, CORS y rechazo del handshake no autorizado.
Las pruebas anteriores de compatibilidad con DEV 1 continúan pasando.

RabbitMQ y sus declaraciones están desactivados solo en las pruebas de contexto.
No se probó un broker RabbitMQ real ni un frontend React. Los eventos de la prueba
WebSocket entran por ProcessScoreUpdate; la cadena de decoder Rabbit se verifica
en las pruebas unitarias y de compatibilidad existentes.

Referencias: [orden de mensajes de Spring](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/ordered-messages.html)
y [cliente STOMP de Spring](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/client.html).
