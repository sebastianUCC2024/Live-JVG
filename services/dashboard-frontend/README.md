# Live-JVG — Dashboard frontend DEV 2

Frontend React + TypeScript + Vite con modo de tiempo real y demostración separada.
El modo predeterminado conecta con dashboard-backend por WebSocket/STOMP usando
los destinos verificados. La demostración sigue disponible mediante su botón o
`/?mode=demo` y utiliza solo fixtures identificados, sin abrir STOMP al backend.

Véase [Fase 6: protocolo, configuración y resultados](docs/phase6-realtime.md).
La [auditoría de integración de Fase 7](docs/phase7-integration.md) registra los
checks ejecutados y el bloqueo de la cadena completa por brokers pendientes de DEV 3.
La [revisión final de Fase 8](docs/phase8-final-review.md) consolida configuración,
alcance autorizado, limitaciones y resultados de aceptación local.

## Inicio en tiempo real

Configurar `.env.local` a partir de `.env.example`, sin versionar credenciales:

```text
VITE_WS_URL=ws://127.0.0.1:8081/ws
VITE_MATCH_IDS=match-001
```

La variable opcional VITE_MATCH_METADATA permite etiquetas de equipos aportadas
por el operador; no son campos del JSON del servidor. Sin ellas se muestra Local
y Visitante. El formulario permite seguir hasta 20 IDs de partidos. No existe un
endpoint de catálogo que el frontend pueda consultar.

## Requisitos y ejecución

Node.js 22.12 o posterior. Verificado con Node 24.21.0 y npm 12.2.0 en Windows.
Desde esta carpeta:

```powershell
npm.cmd ci
npm.cmd run dev
npm.cmd test
npm.cmd run build
npm.cmd run preview
```

En otras plataformas, usar `npm` en lugar de `npm.cmd`. El sufijo evita el bloqueo
de npm.ps1 por la política PowerShell del equipo. No se cambió esa política.
Desarrollo en http://127.0.0.1:5173, con puerto estricto. Preview de producción
usa el puerto que imprime Vite (habitualmente 4173). `dist/` es la compilación.
No se publicaron sitios ni se realizaron commits o push.

Scripts adicionales: `test:watch` (Vitest interactivo) y `test:e2e` (Playwright).
`test:integration` ejecuta los reinicios de un backend aislado con un worker;
requiere REAL_BACKEND_RESTART=1, JAVA17_BIN y VITE_WS_URL con un puerto local
exclusivo. Las instrucciones están en el informe de Fase 7. Los smoke tests
opcionales se omiten explícitamente si no se prepara su entorno.
Las pruebas E2E utilizan Google Chrome local mediante `channel: 'chrome'`, sin
descargar navegadores automáticamente. Si no está instalado, instalar Chrome o
ajustar el canal conscientemente. Playwright arranca y cierra su servidor Vite.

La instalación creó package-lock.json; usar npm ci para reproducirla. Dependencias
principales resueltas: React/React DOM 19.3.1, Vite 7.3.8, TypeScript 5.9.3,
Vitest 4.1.12, React Testing Library 16.3.3 y Playwright 1.65.1. El lockfile
contiene las versiones exactas transitivas.

### Variables y seguridad

VITE_WS_URL, VITE_MATCH_IDS y VITE_MATCH_METADATA se incorporan al bundle durante
desarrollo/compilación y son públicos. No guardar contraseñas, tokens ni credenciales
RabbitMQ en variables VITE_. El frontend solo conecta con el dashboard-backend.
Vite carga .env.local, ignorado por Git; reiniciar Vite o recompilar tras cambiarlo.

El origen del navegador debe estar autorizado mediante DASHBOARD_ALLOWED_ORIGINS
en el backend. El puerto de preview 4173 no está en la lista local por defecto:
si se revisa ese preview con conexión real, añadir explícitamente su origen a la
configuración externa del backend. En un despliegue HTTPS configurar wss://.
La aplicación no incorpora autenticación por usuario ni persistencia de favoritos.

## Organización

```text
src/
  components/    MatchCard, Scoreboard, VarEvent, StatePanel, Icon
  pages/         DashboardPage y pruebas de interacción
                 LiveDashboardPage: snapshots, conexión y eventos recientes
  hooks/         useMatches: carga, validación y cancelación
                 useDashboardStream: conexión y limpieza de suscripciones
  services/      dashboardStream: STOMP, recuperación y estado por partido
                 liveConfig: configuración y nombres opcionales
                 demoMatches: fixtures y servicio asíncrono local
  types/         MatchSnapshot, SnapshotReply, enums y modelo visual
  test/          configuración de React Testing Library
  main.tsx
  App.tsx        selección tiempo real / demostración
  styles.css
e2e/             pruebas Playwright en escritorio y móvil
```

El layout adapta navegación y columnas a móvil. La vista de demostración incluye marcador accesible,
revisión VAR, gol anulado, búsqueda por equipo/competición, filtros y favoritos
de la sesión. El detalle muestra información deportiva del escenario, sin cambiar
marcadores. Favoritos y filtros se reinician al recargar el navegador.

El selector inferior `Escenario de demo` permite revisar carga, jornada vacía y
error. El escenario de carga se mantiene pendiente hasta cambiarlo. El botón
`Restablecer demostración` vuelve a los datos de muestra. `Recargar demo` repite
la carga del escenario seleccionado. No se simula un reloj ni partidos en vivo.

## Correspondencia con dashboard-backend

`types/match.ts` reproduce los campos de `MatchSnapshotResponse`, en snake_case:

| Campo | Tipo TypeScript |
|---|---|
| stream_id, match_id, event_id | string |
| revision, home_score, away_score | number |
| event_type | GOAL / VAR_CHECK / GOAL_ANNULLED / MATCH_CONTINUES |
| var_status | CLEAR / IN_PROGRESS |
| updated_at | string ISO 8601; conserva el original, incluidos nanosegundos |
| reference_event_id | string o null |

`SnapshotReply` es la unión `{ available: true, snapshot: MatchSnapshot }` o
`{ available: false, snapshot: null }`, correspondiente a la recuperación STOMP del
backend. Se consulta de nuevo después de cada conexión.

La validación local comprueba IDs, números enteros seguros y no negativos,
revisión positiva, enums, fecha textual y referencia de anulaciones. No sustituye
la validación del servidor ni verifica existencia de goles. TypeScript usa number;
las revisiones fuera del rango entero seguro se rechazan para no redondearlas.

`DemoMatch` agrega nombres, iniciales, colores de equipos, competición y minuto.
**Estos datos son ficticios y no son campos recibidos del backend actual.** Están
separados del snapshot. Antes de integrar se necesita una fuente de metadatos de
equipos y partidos; no se modificaron contratos compartidos para inventarla.

La UI no recalcula goles o anulaciones: muestra home_score/away_score del snapshot.
El detalle muestra la fecha de la muestra en America/Bogota; el DTO conserva UTC.

## Verificación de la estructura de Fase 5 — 2026-11-14

- `npm.cmd test`: 4 archivos, **26 pruebas aprobadas**.
- `npm.cmd run build`: TypeScript estricto y Vite completados; salida en dist.
- `npm.cmd run test:e2e`: **2 pruebas aprobadas**, Chrome a 1440×1000 y 390×844.
- Capturas de ambas vistas inspeccionadas; sin desbordamiento horizontal.

Las pruebas cubren contratos y datos inválidos, marcador y VAR, favoritos,
detalle, filtros, carga, vacío, error/recuperación, cancelación y copias de fixtures.
Las E2E verifican el render real, filtros, error recuperable y ausencia de sockets
al backend. Vite sí puede abrir su socket local de HMR durante desarrollo;
eso no es una integración STOMP del dashboard.

Capturas locales: `test-results/dashboard-desktop.png` y
`test-results/dashboard-mobile.png`, ignoradas por Git. Los tests iniciales E2E
pasaron pero su cierre quedó bloqueado por permisos del entorno; la ejecución
final completó los dos casos y terminó con código 0.

## Pendientes de integración

Validar la cadena completa con RabbitMQ real y definir una fuente para catálogo y
nombres de equipos. La conexión STOMP, la reconexión y la recuperación por
stream_id/revision ya están implementadas. El backend sigue teniendo estado
volátil: reiniciarlo puede dejar el marcador no disponible hasta el siguiente evento.

Referencias: [Vite](https://vite.dev/guide/) y
[entornos de prueba Vitest](https://vitest.dev/guide/environment.html).
