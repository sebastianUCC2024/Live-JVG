# Fase 4.1 — Validación y endurecimiento de alertas

Base: `3e89b862b2572922d00b21f7d9f2142cf28c9c5a`, rama
`feature/Gabo-react-integration`. Se conservaron los 14 archivos pendientes de
Fase 4. Todo el trabajo de esta fase permanece dentro del frontend.

## Defecto reproducido y corrección

El validador aceptaba fechas imposibles que JavaScript normaliza silenciosamente:
30 de febrero, 29 de febrero en año no bisiesto, 31 de abril y hora 24:00.
Las cuatro pruebas fallaron antes de corregir el código. Ahora se comprueban
calendario gregoriano y rangos horarios antes de aceptar una marca temporal.
Se conserva la precisión de hasta nueve decimales y los offsets admitidos.
La comprobación se aplica a `created_at` y `source_updated_at`.

No se encontraron defectos adicionales que justificaran cambiar el hook de
polling, el componente, el layout o la sincronización del marcador.

## Cobertura de concurrencia

Se añadieron nueve pruebas del hook y cinco del validador/API. La suite incluye:

| Escenario | Comprobación |
| --- | --- |
| Cambio de partido durante solicitud | AbortSignal e ignorar la respuesta antigua |
| Cambio rápido A-B-A | La respuesta antigua de A tampoco reemplaza la sesión nueva de A |
| Cambio/desactivación de origen | Cancelación, limpieza de timers y nuevo checkpoint al habilitar |
| Alertas repetidas | Identidad UUID sin distinguir mayúsculas, conservando el primer contenido |
| Página vacía con cursor nuevo | El siguiente polling utiliza el nuevo cursor |
| Varias páginas has_more | Orden de recepción, pausas y rechazo de ciclos de cursor |
| 409 durante drenaje | Recuperación inicial sin mezclar épocas |
| 400 | Detención y reintento manual desde el inicio |
| 503 persistente | No hay bucle automático; se conserva el estado válido |
| Red temporal o DTO malformado | No avanzar checkpoint y recuperación manual |
| Desmontaje/timeout | AbortController y limpieza de recursos |
| Respuestas fuera de orden | Sesiones obsoletas descartadas aunque el doble ignore cancelación |

## Validación visual local

`playwright.alerts.config.ts` reutiliza las dimensiones y Chrome del proyecto:
1440×1000 y 390×844. Utiliza un Vite temporal exclusivo en 127.0.0.1:5173; rechaza
reutilizar un servidor existente. No instala software. Los orígenes 8081/8082
del navegador son fixtures interceptados, no servicios iniciados. REST y STOMP
se simulan; únicamente el HMR puede comunicarse con el propio Vite local.
Las peticiones HTTP/WS inesperadas se bloquean y hacen fallar las aserciones.

Siete escenarios por viewport: carga/lista/textos extensos, vacío, 503/reintento,
paginación/409, cambio de partido, demo y memory. Incluyen fecha, orden,
deduplicación, contenido HTML como texto, regiones y estados accesibles, navegación
con Tab y activación con Enter. Se comprueba ausencia de desbordamiento horizontal
y errores JavaScript. Se inspeccionaron las capturas de escritorio y móvil.
No es una auditoría WCAG completa ni una comparación de píxeles con una referencia.

La primera ejecución detectó errores en los fixtures nuevos: un título excedía
120 caracteres y se asumía una única solicitud inicial bajo StrictMode. Se
corrigieron las pruebas sin eliminar StrictMode ni relajar la validación del DTO.

## Ejecución y evidencias

Desde `services/dashboard-frontend`, con dependencias y Chrome ya instalados:

```powershell
node node_modules/vitest/vitest.mjs run --reporter=dot
npm.cmd run build
node node_modules/@playwright/test/cli.js test --config playwright.alerts.config.ts --workers=1 --output test-results/phase41-browser-<ejecucion-nueva> --reporter=line
git diff --check
```

Usar un directorio nuevo por ejecución: Playwright limpia su output seleccionado.
No sobrescribir evidencias anteriores. Si el puerto está ocupado, detenerse;
no detener procesos ajenos. Playwright administra el servidor temporal. En esta
sesión Windows, su cierre automático quedó esperando: se detuvo exclusivamente
el PID de Vite identificado en el log de esta ejecución y el runner finalizó con
código 0. Si se reproduce, verificar ese PID antes de detenerlo, esperar la salida
del runner y confirmar que ya no quede el proceso temporal. No usar terminación
global de Node ni Chrome.

Resultados locales de esta fase:

- Vitest: 135 pruebas en 14 archivos; incluye las 121 anteriores.
- Build TypeScript/Vite: correcto.
- Playwright/Chrome: 14 pruebas aprobadas (7 escritorio + 7 móvil), código 0.
- `git diff --check`: sin errores.
- Demo: cuatro tarjetas de fixtures, sin consultas de alertas ni STOMP al backend.
- Memory: catálogo no disponible, marcador por STOMP simulado, sin notificaciones.

Evidencias ignoradas por Git, conservadas localmente:

- `test-results/phase41-local/`: logs de Vitest, build y ejecución visual final.
- `test-results/phase41-browser-final/`: capturas y `.last-run.json` con estado passed.
- `test-results/phase41-browser-run1/` y `phase41-browser-run2/`: intentos anteriores
  conservados. Fase 4 y evidencias previas no fueron borradas.

## Configuración y dependencias reales pendientes

`VITE_NOTIFICATION_ORIGIN` debe ser un origen HTTP(S) aprobado, sin ruta, query,
fragmento ni credenciales; se publica en el bundle. No introducir secretos en
variables VITE_. Reiniciar Vite o recompilar al cambiar configuración.

Alertas activas solo con origen válido y un partido seleccionado del catálogo
durable. Demo no consulta servicios reales y memory/selección manual no monta
notificaciones. El marcador REST/STOMP conserva su propio ciclo de conexión.

La integración real requiere notification-backend con persistencia y consumo
habilitados bajo autorización, URL/puerto coordinados y CORS para el origen
exacto del frontend (por ejemplo `http://127.0.0.1:5173`, distinto de localhost).
En una página HTTPS también se necesita un origen HTTPS. No se configuró CORS
ni se activaron backends, brokers, bases de datos o migraciones en esta fase.

Estas pruebas no certifican CORS, durabilidad de PostgreSQL, RabbitMQ, redelivery
ni el flujo real de eventos. La API inicial continúa limitada a su ventana reciente;
un reset 409 no permite recuperar historia que el servicio no expone. Las sesiones
largas conservan las alertas recibidas en memoria; queda medir el volumen real.
Contrato compartido LiveAlert, identidad tras restauraciones y políticas de
retención siguen requiriendo coordinación del equipo.
