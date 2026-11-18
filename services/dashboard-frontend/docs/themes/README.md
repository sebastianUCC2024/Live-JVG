# Temas visuales — Live-JVG

## Comportamiento

Selector en la cabecera con iconos propios de sol y luna. Opciones Claro y Oscuro; la opción activa se expresa mediante `aria-pressed`. En pantallas pequeñas se conservan los iconos, nombres accesibles y títulos.

La clave localStorage es `live-jvg.theme` (`light`, `dark`). Sin preferencia válida se utiliza Oscuro. Los valores anteriores `auto`, `automatic` y `system` se convierten y persisten como `dark`. Ya no se consulta ni se escucha `prefers-color-scheme`. Los cambios entre pestañas se sincronizan mediante el evento `storage`. Si el almacenamiento no está disponible, el tema funciona durante la sesión.

`public/theme-init.js` se ejecuta de forma síncrona en head antes de React y aplica data-theme, color-scheme y theme-color. El selector utiliza la misma política de resolución, cubierta por pruebas separadas. La página no espera a un efecto React para pintar el tema inicial.

Los tokens semánticos de `themes.css` cubren fondos, superficies, texto, controles y estados positivos/VAR/anulación. Se sustituyeron los colores aislados del rediseño, conservando los degradados decorativos de los escudos. La hoja anterior se expandió a reglas legibles para revisar los cambios.

Cambiar el tema actualiza únicamente el selector y los atributos de documento: no remonta Dashboard ni sus hooks. Se conservan favoritos, búsqueda, filtros, partido seleccionado, detalle, marcador y conexión STOMP. No se modifica backend, contratos, bases, brokers, Workers ni apuestas.

## Verificación real

- Build: PASS.
- ESLint: PASS, cero warnings.
- Vitest: 19 archivos, 165 pruebas aprobadas, cero fallos y cero omitidas (148 existentes y 17 nuevas).
- Pruebas nuevas: preferencia inicial, persistencia/recuperación, elección manual independiente del sistema, migración de preferencias anteriores y ausencia de listeners del sistema, almacenamiento inválido o bloqueado, sincronización entre pestañas, conservación de estado demo y transporte conectado, inicialización antes de React.
- Chromium: modos servidor y demo, Claro/Oscuro a 1440 y 390 px; comprobación adicional sin desbordamiento a 320, 820 y 1280 px.
- Navegador: recarga persistente, independencia respecto al esquema del sistema, foco visible por teclado, movimiento reducido y ausencia de errores de página/consola. Se bloqueó deliberadamente la carga de React para comprobar que el script inicial aplica el tema por sí solo.
- Contraste: 32 combinaciones de tokens de texto/fondo, todas >=4,5:1; mínimo medido 5,99:1. Esto no constituye una certificación completa WCAG.
- Se consultaron cinco eventos históricos y tres alertas existentes. No se generaron nuevos eventos ni se ejecutó otro E2E deportivo.

Evidencias: [validación visual](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/visual-validation.json), [contrastes medidos](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/contrast.json).

| Vista | Claro | Oscuro |
| --- | --- | --- |
| Servidor, escritorio | [Captura](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/live-light-desktop.png) | [Captura](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/live-dark-desktop.png) |
| Servidor, móvil | [Captura](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/live-light-mobile.png) | [Captura](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/live-dark-mobile.png) |
| Demo, escritorio | [Captura](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/demo-light-desktop.png) | [Captura](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/demo-dark-desktop.png) |
| Demo, móvil | [Captura](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/demo-light-mobile.png) | [Captura](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/dashboard-frontend/docs/themes/demo-dark-mobile.png) |

## Cómo probar

```sh
cd services/dashboard-frontend
npm ci
npm run lint
npm test
npm run build
npm run dev -- --port 5185
```

Abrir `http://127.0.0.1:5185/?mode=demo` y seleccionar Claro/Oscuro en la cabecera. Recargar para comprobar persistencia. Cambiar la apariencia del sistema operativo no debe alterar la elección manual.

Para consultar el laboratorio existente, sin modificar su configuración, se mantiene el proxy local opt-in incorporado en el PR #36:

```sh
LIVE_JVG_PREVIEW_PROXY=1 \
VITE_WS_URL=ws://127.0.0.1:5185/ws \
VITE_NOTIFICATION_ORIGIN=http://127.0.0.1:5185 \
VITE_MATCH_IDS=e2e-e1248a11f4cc \
npm run dev -- --host 127.0.0.1 --port 5185
```

El proxy requiere Dashboard/Notification ya disponibles en 18190/18192 y permite únicamente consultas HTTP GET/HEAD y el WebSocket existente. No se cambia su implementación en esta entrega. El frontend anterior en 5173 y el preview 5184 no se sustituyen ni detienen.

## Git y límites

Base: dev `aa1a7c374cd8a29ded9a3efdae8d9cf69142c029`, con PR #36 fusionado. Rama nueva: `feature/valeria-color-themes`. Entrega hacia dev sin merge automático.

ORDER02 continúa abierto. Los temas no alteran el orden recibido ni certifican infraestructura. No se añadieron dependencias npm; se mantienen las limitaciones de dependencias de desarrollo documentadas en el rediseño.
