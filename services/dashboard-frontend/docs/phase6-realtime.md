# Fase 6 — Marcadores y VAR en tiempo real

## Contrato inspeccionado

Se leyeron DashboardWebSocketConfiguration, SnapshotSubscriptionController,
StompInboundInterceptor y MatchSnapshotResponse del dashboard-backend actual.

- Conexión WebSocket nativa: `/ws`, protocolo STOMP, sin SockJS.
- SUBSCRIBE live: `/topic/matches/{matchId}/score`.
- SUBSCRIBE de recuperación: `/app/matches/{matchId}/snapshot`.
- Consulta REST existente: `/api/matches/{matchId}/score`, no necesaria para este
  cliente porque la recuperación se hace por la misma conexión STOMP.
- Mensaje live: stream_id, revision, match_id, home_score, away_score, event_id,
  event_type, updated_at, var_status y reference_event_id.
- Recuperación: `{available:true,snapshot:{...}}` o `{available:false,snapshot:null}`.

No se inventaron rutas ni campos de red, ni se conectó el navegador a Kafka o
RabbitMQ. El cliente no envía SEND: únicamente CONNECT, SUBSCRIBE y su limpieza.
El estado de equipos y minuto no existe en el DTO. La vista live muestra Local
y Visitante cuando faltan etiquetas y no inventa un minuto de juego.

## Configuración

Instalar con `npm.cmd ci` y ejecutar `npm.cmd run dev`. Tras cambiar variables de
Vite hay que reiniciar el servidor de desarrollo o recompilar.

| Variable | Uso |
|---|---|
| VITE_WS_URL | URL completa del endpoint existente; ejemplo ws://127.0.0.1:8081/ws |
| VITE_MATCH_IDS | IDs separados por comas; predeterminado match-001 |
| VITE_MATCH_METADATA | JSON opcional indexado por ID, con home, away y competition |

Las etiquetas son configuración del operador, no datos recibidos del servidor.
Ejemplo: `{"match-001":{"home":"Equipo A","away":"Equipo B"}}`.
El fallback de URL usa ws/wss según la página, su hostname y el puerto 8081.
El origen del frontend debe estar permitido por DASHBOARD_ALLOWED_ORIGINS del
backend. El modo demo es explícito y se desmonta al volver a tiempo real.

La selección inicial elimina IDs repetidos/no válidos y limita a 20. El formulario
acepta letras, números, punto, guion y guion bajo; los IDs son sensibles a
mayúsculas. El servidor no permite wildcard en suscripciones. No se intenta
descubrir partidos ni deducir sus IDs de nombres.

## Arquitectura

- `dashboardStream.ts`: adaptador @stomp/stompjs y SnapshotStore por partido.
- `useDashboardStream.ts`: administra ciclo de vida React, cambios de IDs/URL,
  reconexión manual y limpieza al desmontar o cambiar de modo.
- `LiveDashboardPage.tsx`: muestra conexión, marcadores, VAR e historial reciente.
- `App.tsx`: selección explícita entre tiempo real y demo, independientes.
- `MatchCard`: reutilizada con identificación correcta de la fuente de datos.

## Recuperación y duplicados

En cada onConnect se renuevan primero la suscripción live y después la consulta
de snapshot, por partido y por la misma sesión. Durante la consulta se guardan
las últimas 20 actualizaciones recibidas. Se aplica el snapshot y después las
actualizaciones posteriores del mismo stream. El backend preserva el orden de
recepción de las suscripciones.

Una revisión igual o menor de ese stream no reemplaza el marcador. Los eventos
visibles se deduplican por event_id dentro de partido/stream y el historial se
limita a 20 entradas. Se retienen hasta 10000 identidades por partido; al superar
ese límite se retira la más antigua. No es un historial deportivo completo.
Si un duplicado llega con revisión nueva, puede actualizarse el snapshot completo,
pero no se añade otro evento visible con el mismo ID.

Si el snapshot autoritativo tiene otro stream_id se descarta el estado anterior,
incluido el historial, antes de aplicar la nueva revisión. Callbacks de conexiones
anteriores se ignoran mediante un token de sesión. Si available es false y no
hay actualizaciones nuevas, no se muestra marcador inventado. Se conserva el
último dato al perder conexión, marcado como sin actualizar; si la recuperación
confirma que no hay estado en el servidor, se retira ese dato anterior.

Los marcadores siempre se asignan desde home_score/away_score. Ni GOAL ni
GOAL_ANNULLED incrementan/restan cifras en el cliente. VAR se representa a partir
de event_type/var_status. updated_at se conserva textual y su presentación usa
America/Bogota; la comparación de orden usa revision, no fechas.

## Reconexión y mensajes inválidos

Heartbeats de 10 segundos, timeout de conexión de 8 segundos, espera exponencial
de reconexión entre 1 y 30 segundos. Tras ocho cierres fallidos consecutivos sin
completar la recuperación se desactiva la reconexión automática. El botón
Reconectar inicia un cliente nuevo. Completar la recuperación restablece la
cuenta de fallos. Un snapshot que no responde en ocho segundos fuerza reconexión.
Los estados visibles son conectando, conectado, reconectando y sin conexión;
recuperando estado se muestra también por partido.

El decoder valida estructura, IDs, partido esperado, enum, enteros seguros,
revisión, fecha y referencia de anulación. Un mensaje inválido incrementa un
aviso y deja intacto el estado válido. Una respuesta inicial inválida no se acepta;
el timeout de recuperación evita quedar esperando indefinidamente. No se muestran
cuerpos ni errores técnicos del servidor. La reconexión STOMP renueva las
suscripciones porque la librería no las recupera por sí sola.

No hay garantía exactly-once ni recuperación durable: la capacidad de recuperar
un marcador depende del snapshot en memoria del backend. La revisión ordena la
proyección local del servidor, no corrige un replay deportivo fuera de orden.

## Pruebas y alcance

Comandos:

```powershell
npm.cmd test
npm.cmd run build
npm.cmd run test:e2e
```

La suite de Vitest valida goles local/visitante, revisión y anulación VAR,
duplicados, revisiones atrasadas, mensajes inválidos, desconexión/reconexión,
recuperación con buffer, cambio de stream, falta de snapshot, límite de reintentos,
limpieza de recursos y nombres opcionales. También conserva las pruebas de demo.

Playwright usa Chrome en escritorio y móvil. Los casos deportivos ejecutan el
cliente @stomp/stompjs real del navegador contra un servidor WebSocket simulado
con el formato STOMP del backend; no son una prueba de RabbitMQ ni publicación
desde DEV 1. Comprueban además el diseño sin desbordamiento y la reconexión real
del cliente después del cierre del socket simulado.

El smoke opcional se habilita con LIVE_BACKEND_SMOKE=1 y requiere un backend real
vacío al que apunte VITE_WS_URL. Verifica CONNECT/CONNECTED y SnapshotReply no
disponible, sin simular el servidor en esos casos. Se omite explícitamente cuando
no se solicita. Para repetirlo, iniciar el JAR existente con puerto exclusivo y
Rabbit desactivado; no usar una instancia compartida con estado de partidos.

En esta fase se inició el JAR de dashboard-backend en 18081 con
`--spring.rabbitmq.listener.simple.auto-startup=false --spring.rabbitmq.dynamic=false
--management.health.rabbit.enabled=false`. El frontend de prueba usó
VITE_WS_URL=ws://127.0.0.1:18081/ws. No se modificaron fuentes del backend.

Resultado final del 2026-11-14 (America/Bogota), Node 24.21.0 y npm 12.2.0:

| Verificación | Resultado |
|---|---|
| Vitest | 7 archivos, 50 pruebas aprobadas |
| TypeScript estricto y Vite | Compilación correcta, dist generado |
| Playwright con smoke habilitado | 6 pruebas aprobadas, ninguna omitida |
| Chrome | Escritorio 1440×1000 y móvil 390×844, sin desbordamiento horizontal |
| Backend real | Handshake STOMP y snapshot no disponible confirmados |

El backend temporal se detuvo al terminar. No se probó la cadena
DEV 1 → RabbitMQ → dashboard-backend con eventos reales en esta fase.
Capturas: `test-results/live-desktop.png`, `test-results/live-mobile.png` y las
capturas de demo. Son artefactos locales ignorados por Git.

Referencias: [Client de STOMP.js](https://stomp-js.github.io/api-docs/latest/classes/Client.html)
y [uso de STOMP.js](https://stomp-js.github.io/guide/stompjs/using-stompjs-v5.html).
