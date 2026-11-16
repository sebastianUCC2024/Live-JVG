# Fase 2 — Integración React con dashboard durable

Alcance: solo dashboard-frontend. Sin cambios en backend, contratos, Compose,
infraestructura ni migraciones. No implementa notificaciones.

## Ejecución

Desde `services/dashboard-frontend`, crear `.env.local` con:

```text
VITE_DASHBOARD_ORIGIN=http://127.0.0.1:18080
```

```powershell
npm.cmd ci
npm.cmd test
npm.cmd run build
npm.cmd run dev
```

Vite escucha en `http://127.0.0.1:5173`. El backend debe estar previamente
preparado por el equipo; estos comandos no arrancan Kafka, RabbitMQ ni PostgreSQL.
No colocar secretos en variables VITE_, que son públicas.

| Modo | Origen backend | Selección y recuperación |
|---|---|---|
| Compose durable | http://127.0.0.1:18080 | Catálogo, nombres e historial REST, snapshots y actualizaciones STOMP |
| Durable en host | http://127.0.0.1:8080 | Mismas APIs; configurar origen explícito |
| Memory | http://127.0.0.1:8081 | IDs manuales, snapshot y eventos de la sesión por STOMP |
| Demostración | Sin backend | `/?mode=demo`, fixtures identificados, sin REST/STOMP |

VITE_WS_URL conserva precedencia. REST se deriva del endpoint STOMP efectivo
para usar el mismo servidor. VITE_MATCH_IDS es un fallback manual y
VITE_MATCH_METADATA solo aporta etiquetas cuando no hay nombres REST.

## Contratos y sincronización

`dashboardApi` valida el catálogo de `GET /api/matches`, el detalle de
`GET /api/matches/{id}` y el snapshot de `GET /api/matches/{id}/score`.
Solo este último convierte 404 en ausencia de snapshot. Los otros errores se
muestran con posibilidad de reintentar; no se reemplazan por datos simulados.

El detalle contiene eventos sin revisión individual. Se consulta antes del snapshot
y se comprueba que `last_event_id` y el primer evento coincidan con `event_id`
del snapshot. Si hay una publicación intermedia, se reintenta una vez. No se
asignan revisiones inventadas ni se ordena por timestamps: el detalle ya llega
en orden descendente de secuencia de inbox.

Se conservan las suscripciones `/topic/matches/{id}/score` y
`/app/matches/{id}/snapshot`, junto con la recuperación, heartbeat, backoff y
deduplicación existentes. REST nunca modifica el almacén STOMP. Dentro de una
misma época se presenta el snapshot de mayor revisión. Entre épocas diferentes
prevalece la recuperación STOMP; se descarta el historial REST incompatible.
Una respuesta STOMP explícitamente vacía también invalida REST previo.

La lista combina eventos STOMP posteriores a la revisión del snapshot REST con
el historial REST, elimina IDs repetidos y conserva como máximo 30. Las respuestas
de consultas canceladas por cambio de partido, URL o ciclo de recuperación se
ignoran aunque el transporte no respete AbortSignal. La reconexión vuelve a
consultar REST. No existe paginación.

## Límites y validación

Los tests locales usan REST y transporte STOMP controlados; no certifican el flujo
Kafka → RabbitMQ → PostgreSQL → navegador. Playwright contra servicios reales,
reinicios y migraciones requieren autorización y entorno del equipo.

Con tráfico continuo puede fallar la comprobación de consistencia de las dos
consultas REST; se muestra el error y se permite reintentar mientras STOMP sigue
funcionando. El historial completo anterior a los últimos 30 eventos no se ofrece.
La configuración CORS del backend debe permitir el origen Vite; preview 4173
requiere configuración externa explícita.

## Resultado local de esta fase

- Rama `feature/Gabo-react-integration`, base `9d8aae2`.
- `npm.cmd test -- --reporter=dot`: 84 pruebas aprobadas en 10 archivos
  (59 existentes y 25 nuevas).
- `npm.cmd run build`: TypeScript y Vite completados; bundle generado en `dist/`.
- `git diff --check`: sin errores de espacios.
- No se ejecutaron Playwright ni pruebas contra infraestructura real.

Fue necesario ejecutar `npm.cmd ci --no-audit --no-fund` porque no existía
`node_modules`. No se cambiaron package.json ni package-lock.json. El primer
intento restringido falló por permisos de red/caché; el segundo completó la
instalación. npm bloqueó el postinstall de esbuild según su política local;
el build funcionó con el binario disponible sin cambiar esa política.

Pendientes del equipo: DEV 1 debe mantener el contrato ScoreUpdate y validar
sus artefactos; DEV 3 debe preparar configuración e infraestructura autorizadas.
Notificaciones requiere otra fase y aprobación previa de LiveAlert/topología.
