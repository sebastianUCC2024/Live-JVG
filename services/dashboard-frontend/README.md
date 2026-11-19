> Guía actual de instalación y validación: [clon limpio](../../docs/CLEAN_CLONE.md). Las cifras y declaraciones de ejecución fechadas más abajo son históricas; no certifican una ejecución nueva.

> MVP persistente: configura `VITE_DASHBOARD_ORIGIN=http://127.0.0.1:18080` en `.env.local` y ejecuta `npm.cmd run dev`. React descubre IDs y nombres mediante `/api/matches`. No se necesitan endpoints nuevos ni cambios en contratos. La integración REST/STOMP se describe en [Fase 2 durable](docs/phase2-durable-react.md).

# Live-JVG — Dashboard frontend DEV 2

Alertas persistidas opcionales: [Fase 4 — integración de notificaciones](docs/phase4-notification-alerts.md).
Definir `VITE_NOTIFICATION_ORIGIN` con el origen HTTP aprobado habilita su consulta
en la selección durable desde catálogo. Sin esa variable no se consulta el servicio;
demo y selección manual/memory conservan su funcionamiento anterior.

Validación local de alertas, concurrencia, escritorio/móvil y límites de estas
pruebas: [Fase 4.1 — validación y endurecimiento](docs/phase41-alert-validation.md).

Cierre local actual y requisitos pendientes de integración conjunta:
[DEV 2 — cierre y preparación E2E](docs/dev2-local-closure-and-e2e.md).

Configuración vigente por modo: [MVP, host durable y memory](../../docs/CLEAN_CLONE.md).
También se puede omitir VITE_WS_URL y definir VITE_DASHBOARD_ORIGIN con el origen
HTTP del dashboard; VITE_WS_URL conserva precedencia si ambas están definidas.

Frontend React + TypeScript + Vite con modo de tiempo real y demostración separada.
El modo predeterminado conecta con dashboard-backend por WebSocket/STOMP usando
los destinos verificados. La demostración sigue disponible mediante su botón o
`/?mode=demo` y utiliza solo fixtures identificados, sin abrir STOMP al backend.

Véase [Fase 6: protocolo, configuración y resultados](docs/phase6-realtime.md).
La [auditoría de integración de Fase 7](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/phase7-integration.md) registra evidencia
histórica y bloqueos de aquella etapa. La configuración actual está en los modos
de ejecución; la validación vigente está en el [informe 9E](../../docs/integration/local-academic-v1/report.md).
La [revisión final de Fase 8](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/phase8-final-review.md) consolida configuración,
alcance autorizado, limitaciones y resultados de aceptación local.

## Inicio en tiempo real

Configurar `.env.local` a partir de `.env.example`, sin versionar credenciales:

```text
VITE_DASHBOARD_ORIGIN=http://127.0.0.1:18080
```

El catálogo permite seleccionar un partido y utiliza sus nombres reales. El detalle
REST recupera hasta 30 eventos persistidos; STOMP mantiene el marcador actualizado.
La selección manual permite seguir hasta 20 IDs y conserva el modo memory, que no
ofrece catálogo ni historial REST. Para memory usa origen `http://127.0.0.1:8081`
y los IDs reales mediante `VITE_MATCH_IDS` o el formulario. Sin configuración se
conserva el fallback histórico a memory 8081. Demostración usa `/?mode=demo` y no
consulta REST ni abre STOMP.

VITE_MATCH_METADATA aporta etiquetas opcionales para memory; los nombres REST
tienen prioridad. VITE_WS_URL prevalece sobre VITE_DASHBOARD_ORIGIN y determina
también el origen HTTP(S) de REST, evitando conectar catálogo y STOMP a servidores
distintos. Se requiere un endpoint WS(S) terminado en `/ws`, sin credenciales ni
query. Reinicia Vite después de cambiar `.env.local`.

## Requisitos y ejecución

Node.js 22.13.0 o posterior (CI: Node 24). Verificado con Node 24.21.0 y npm 12.2.0 en Windows.
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
exclusivo. Las instrucciones vigentes están en la sección «Pruebas y recuperación: instrucciones consolidadas» de este README. Los smoke tests
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

## Pruebas y recuperación: instrucciones consolidadas

Esta sección conserva las instrucciones operativas útiles de los informes históricos de Fases 7/8. No registra ejecuciones nuevas. Para instalar y comprobar los siete módulos desde cero, usar la [guía de clon limpio](../../docs/CLEAN_CLONE.md); para resultados y límites académicos, usar la [matriz de aceptación](../../docs/ACADEMIC_ACCEPTANCE.md).

Desde la carpeta del frontend, con Node >=22.13.0:

```sh
npm ci
npm test
npm run lint
npm run build
```

`npm run test:e2e` utiliza Playwright, Chrome local y proyectos desktop/mobile. Su configuración crea un servidor Vite en 127.0.0.1:5173 y puede reutilizar uno existente fuera de CI: revisar el puerto y no apuntarlo a una demostración compartida. Las pruebas deportivas usan STOMP.js real con un servidor WebSocket simulado; no certifican Kafka/RabbitMQ. `LIVE_BACKEND_SMOKE=1` habilita casos que requieren un backend vacío ya iniciado y aprobado; dejarlo sin definir en la suite local normal.

### Reinicio real aislado del backend, sin brokers

Es una prueba opt-in que crea y detiene procesos Java propios. Requiere autorización para esa ejecución local, Java 17, Chrome instalado, el JAR recién compilado del Dashboard y dos puertos locales libres: 5173 para Vite y un puerto exclusivo para el backend. No usar puertos de laboratorios ni procesos ya iniciados. La prueba rechaza un puerto backend ocupado y no termina procesos preexistentes.

Preparar el JAR desde la raíz, sin limpiar artefactos en uso ni copiar JAR de otros worktrees:

```sh
mvn -B -ntp -f services/dashboard-backend/pom.xml verify
```

Después, desde `services/dashboard-frontend`, en macOS y solo tras verificar los puertos:

```sh
JAVA17_BIN="$(/usr/libexec/java_home -v 17)/bin/java" \
VITE_WS_URL=ws://127.0.0.1:18081/ws \
REAL_BACKEND_RESTART=1 LIVE_BACKEND_SMOKE=0 npm run test:integration
```

18081 es un ejemplo: sustituirlo por un puerto libre aprobado; no utilizarlo si ya pertenece a otro servicio. En PowerShell configurar `$env:JAVA17_BIN`, `$env:VITE_WS_URL`, `$env:REAL_BACKEND_RESTART='1'` y `$env:LIVE_BACKEND_SMOKE='0'`, y ejecutar `npm.cmd run test:integration`. Limitar esas variables a la sesión de la prueba y retirarlas después.

El script versionado ejecuta un worker Playwright, perfil backend `memory`, listeners Rabbit y RabbitAdmin desactivados y health Rabbit excluido. No utiliza PostgreSQL, no ejecuta Flyway ni publica eventos. Verifica conexión, snapshot no disponible sin inventar un 0-0, desconexión/reconexión y recuperación tras reiniciar solo su proceso; guarda capturas y logs en `test-results/` y cierra sus procesos en `finally`. Sin `REAL_BACKEND_RESTART=1` se omite explícitamente. No ejecutar esta prueba contra un backend compartido ni confundirla con el smoke de una instancia ya iniciada.

### Recuperación y límites del cliente

En cada conexión el cliente suscribe `/topic/matches/{matchId}/score` y solicita snapshot mediante `/app/matches/{matchId}/snapshot` sobre `/ws`. Usa `stream_id` y `revision` para continuidad local; `updated_at` no es una secuencia global. Descarta callbacks de sesiones anteriores, duplicados y revisiones antiguas; conserva el último dato como desactualizado durante una desconexión. El timeout es 8 segundos, la espera de reconexión aumenta hasta 30 segundos y ocho fallos consecutivos sin recuperación detienen el reintento automático; la interfaz permite reconectar manualmente.

Una conexión STOMP activa no acredita que un mensaje haya atravesado Kafka/Rabbit ni que exista un partido en curso. El perfil `memory` pierde su proyección al reiniciar; el modo durable consulta catálogo/historial y necesita PostgreSQL ya provisionado. No se resuelve ORDER02 global ni se demuestra exactly-once desde el navegador.

Conservar logs y capturas de una ejecución antes de otra prueba Playwright o de limpiar salidas generadas. Los resultados anteriores de Fases 7/8 y las capturas del rediseño permanecen archivados en el historial Git y respaldos externos; no son requisitos de instalación.
