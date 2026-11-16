# Fase 4 — Alertas persistidas en React

Integración solo en dashboard-frontend, basada en AlertController/AlertResponse del
notification-backend actual. Sin cambios de backend, contratos compartidos,
Compose, CI ni migraciones. Sin WebSocket de notificaciones ni acceso del navegador
a brokers. Las actualizaciones del marcador conservan su REST/STOMP independiente.

## Configuración pública y modos

En `.env.local` del frontend, definir únicamente orígenes aprobados:

```text
VITE_DASHBOARD_ORIGIN=http://127.0.0.1:18080
# Ejemplo de puerto; confirmar su disponibilidad con infraestructura:
VITE_NOTIFICATION_ORIGIN=http://127.0.0.1:8082
```

El puerto de notificaciones es configurable en el backend; 8082 también aparece
como default del archiver, por lo que no constituye una asignación aprobada para
ejecutar ambos servicios en el host. No se usa ningún puerto por defecto en el
cliente de alertas. VITE_NOTIFICATION_ORIGIN debe ser un origen HTTP(S), sin
credenciales, ruta, query ni fragmento. En una página HTTPS usar HTTPS.

La variable VITE_ se publica en el bundle: no incluir secretos, tokens ni passwords.
El cliente no envía cookies ni credenciales. Reiniciar Vite/recompilar tras cambiar
variables. El backend requiere su configuración CORS de notificaciones autorizada
para el origen del frontend (desarrollo: http://127.0.0.1:5173).

- **Durable:** sección para el partido seleccionado en un catálogo REST válido.
  Solo consulta alertas cuando también existe un origen de notificaciones válido.
- **Sin origen:** sección desactivada, sin peticiones ni puerto inferido.
- **Origen inválido:** diagnóstico de configuración, sin peticiones.
- **Memory / selección manual:** conserva el comportamiento previo; no monta el
  consumidor de alertas. Un error del catálogo no activa notificaciones por IDs
  supuestos, aunque exista la variable de origen.
- **Demo:** conserva fixtures y componentes anteriores; no monta esta integración
  y no consulta REST ni abre conexiones reales.

## API real y validación

```text
GET /api/alerts?match_id={id}&limit=50
GET /api/alerts?match_id={id}&after={cursor}&limit=50
```

Envelope: items, next_cursor, has_more. Cada item tiene contract_version=1.0,
alert_id UUID, match_id, alert_type, source_event_id, source_updated_at,
created_at, reference_event_id, home_score, away_score, var_status y content
(locale=es, title, message). Se verifican tipos, partido, fechas, enums, marcadores
Java int no negativos, límites de contenido y referencia obligatoria de anulación.
Se toleran propiedades adicionales sin inventar propiedades requeridas.

La interfaz muestra tipo, título, mensaje y fecha de creación en America/Bogota.
No inventa nombres, minutos o jugadores ni utiliza alertas para recalcular el
marcador. MATCH_CONTINUES es un valor del DTO reconocido; la política vigente del
backend lo registra sin generar una alerta visible.

## Polling y consistencia

- Una petición a la vez por sesión. No se utiliza setInterval.
- Primera carga sin cursor; después se conserva el último checkpoint confirmado.
- Sin backlog, consulta cada 5 segundos después de completar la anterior.
- has_more=true: pausa de 250 ms entre páginas; se continúa por el cursor devuelto.
- Un cursor repetido/cíclico durante el drenaje provoca error controlado.
- AbortController y timeout de 10 segundos por consulta.
- Cambio de partido/origen, desactivación o desmontaje cancela petición y timers.
- Cada ejecución conserva una guarda de vigencia: una respuesta antigua se ignora
  incluso si el transporte de prueba no respeta AbortSignal.
- Identidad visual por alert_id UUID, sin duplicados entre páginas. Se conserva el
  primer contenido recibido para una identidad ya mostrada.
- El orden procede de las páginas del servidor, no de ordenar timestamps. La
  interfaz presenta lo más reciente primero, invirtiendo el orden de recepción.

HTTP 409 incremental inicia una recuperación sin cursor. El contenido anterior
permanece durante la carga; una página inicial válida lo reemplaza para evitar
mezclar épocas. Una nueva invalidación dentro de 30 segundos o un 409 de la página
inicial detiene la recuperación automática. El botón permite reintentar desde el
inicio. HTTP 400 detiene polling; su reintento manual también vuelve al inicio.

HTTP 503, errores de red, JSON/DTO inválido o timeout detienen reintentos automáticos
y conservan alertas válidas con aviso de posible desactualización. Reintentar tras
un error transitorio continúa desde el último cursor confirmado. Una respuesta
rechazada nunca avanza el cursor. Un fallo de alertas no detiene STOMP del marcador.

## Verificación local y límites

Con las dependencias ya instaladas:

```powershell
npm.cmd test -- --reporter=dot
npm.cmd run build
```

Vitest/RTL usan fetch y transporte STOMP controlados: verifican la lógica del
cliente y regresiones, no disponibilidad de PostgreSQL/RabbitMQ ni configuración
CORS real. No se ejecutó Playwright contra servicios ni se certificó integración
extremo a extremo en esta fase. El layout incorpora una columna de alertas en
pantallas pequeñas; la revisión visual real sigue pendiente.

La API inicial entrega solo su ventana reciente (hasta 50 en esta consulta), sin
paginación hacia el pasado. Tras 409, esa ventana es la fuente de recuperación;
el cliente no puede reconstruir historial que la API no ofrece. Durante una
sesión conserva las alertas recibidas; sesiones muy largas requieren observar
volumen/memoria antes de introducir retención visual o virtualización.

Para integración real se necesitan origen/puerto aprobados, backend con esquema
migrado y almacenamiento/consumo habilitados bajo autorización, CORS correcto y
eventos reales producidos por el flujo del proyecto. Nada de ello se activó aquí.

## Resultado de esta fase

- Base `3e89b862b2572922d00b21f7d9f2142cf28c9c5a`, rama
  `feature/Gabo-react-integration`, árbol inicialmente limpio.
- Dependencias existentes reutilizadas; no instalación ni cambios de package.json/lockfile.
- Vitest: 121 pruebas aprobadas en 14 archivos, cero fallos (84 anteriores + 37 nuevas).
- Build TypeScript/Vite correcto. Evidencia local ignorada por Git:
  `test-results/phase4/vitest.log`; bundle en `dist/`.
- Sin cambios fuera del frontend, infraestructura, migraciones, publicaciones,
  pruebas contra servicios reales, commits, push o merges.
