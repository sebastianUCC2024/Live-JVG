# Sports Command Center — revisión visual

Rama: `feature/valeria-sports-command-center`. Base: `d1c3cddefa3ebe8406bd7a27ffdd198c5f494163`.

## Alcance

Rediseño exclusivo de React: navegación lateral, cabecera, tarjetas, detalle con línea de tiempo y alertas. Azul noche, lima, ámbar para VAR y rosa para anulaciones. Iconos SVG propios y gráficos CSS; sin imágenes externas ni fuentes remotas.

Se conservan endpoints, hooks de datos, filtros, favoritos, selección de partidos, reconexión, consultas REST y suscripción STOMP. La línea de tiempo conserva el orden recibido: no ordena por timestamp ni resuelve ORDER02. No se modifican backend, contratos, apuestas o infraestructura.

Los datos del servidor tienen identificación explícita. Una conexión activa no implica un partido en curso. Los contadores derivan únicamente de los partidos seleccionados y estados disponibles. La demo conserva sus fixtures existentes, identificados como simulación. Su detalle muestra solamente la última actualización disponible, no un historial inventado.

## Validación ejecutada

- `npm run build`: PASS.
- `npm run lint`: PASS, cero warnings.
- `npm test -- --reporter=dot`: 17 archivos, 148 pruebas aprobadas, cero fallidas u omitidas.
- Cinco pruebas nuevas: fuente demo/servidor, enlace de salto por teclado, orden recibido de eventos, referencias/marcador y estados sin historial.
- Navegador Chromium real: ambos modos, escritorio 1440 px y anchos 1280, 820, 390 y 320 px. Sin desbordamiento horizontal ni errores de página.
- Navegación por teclado: primer foco en enlace de salto, foco visible. Preferencia de movimiento reducido respetada.
- Datos persistidos observados: marcador 0-0, cinco eventos históricos y tres alertas. No se publicaron eventos para esta revisión.
- `git diff --check`: PASS.

El detalle de las comprobaciones de navegador está en [visual-validation.json](visual-validation.json). Estas verificaciones no constituyen una auditoría completa WCAG ni un nuevo E2E deportivo.

## Capturas reales

| Vista | Antes | Después |
| --- | --- | --- |
| Servidor, escritorio | [Antes](screenshots/before-live-desktop.png) | [Después](screenshots/after-live-desktop.png) |
| Demo, escritorio | [Antes](screenshots/before-demo-desktop.png) | [Después](screenshots/after-demo-desktop.png) |

[Detalle con línea de tiempo](screenshots/after-live-detail-desktop.png), [móvil con datos del servidor](screenshots/after-live-mobile.png), [demo móvil](screenshots/after-demo-mobile.png), [portátil](screenshots/after-live-laptop.png).

## Ejecución local

Requiere Node >=22.13.0. Desde la raíz del repositorio:

```sh
cd services/dashboard-frontend
npm ci
npm run lint
npm test
npm run build
npm run dev -- --port 5184
```

La demo está en `http://127.0.0.1:5184/?mode=demo`. Para datos reales se conservan las variables existentes documentadas en el README del módulo.

Para revisar en paralelo con la demostración existente, exclusivamente cuando Dashboard y Notification ya están disponibles en los puertos locales 18190 y 18192:

```sh
LIVE_JVG_PREVIEW_PROXY=1 \
VITE_WS_URL=ws://127.0.0.1:5184/ws \
VITE_NOTIFICATION_ORIGIN=http://127.0.0.1:5184 \
VITE_MATCH_IDS=e2e-e1248a11f4cc \
npm run dev -- --host 127.0.0.1 --port 5184
```

El proxy de revisión es opt-in, local y rechaza métodos HTTP distintos de GET/HEAD. Reenvía las rutas existentes y el handshake WebSocket con el origen local ya autorizado por la demostración. No cambia CORS del backend ni crea endpoints. El puerto 5184 evita sustituir la demostración 5173. Finalizar únicamente este proceso Vite restaura la situación anterior, sin tocar servicios o datos.

## Componentes

Nuevos: `CommandShell`, `EventTimeline`, sus pruebas y estilos `command-center.css`.
Actualizados: `App`, `DashboardPage`, `LiveDashboardPage`, `MatchCard`, `NotificationAlerts` e `Icon`. Configuración: ESLint, herramientas de lint y proxy Vite opt-in.

## Limitaciones y seguimiento

`npm audit` informa 11 dependencias de desarrollo con severidad alta relacionadas con picomatch: cinco ya presentes en la base y seis dependientes adicionales a través de typescript-eslint. No se aplicó `audit fix --force`; requiere seguimiento independiente del diseño. El build y las pruebas pasan, pero no se afirma ausencia de vulnerabilidades.

El entorno original en 5173 permanece intacto (HTTP 200 y mismo SHA-256 de index.html antes/después). No se reiniciaron contenedores ni se modificaron datos o configuración backend.
