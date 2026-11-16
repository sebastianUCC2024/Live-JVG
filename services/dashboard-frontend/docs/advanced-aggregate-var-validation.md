# DEV 2 — Validación de lectura del estado VAR agregado

Base local: `7f22d1290113dc8e9c68c6c84e6ab401339b8eda`, rama
`feature/Gabo-react-integration`. Se reutilizaron React, tipos, REST/STOMP,
polling y configuración existentes. No se modificó código productivo frontend,
dependencias, contratos compartidos ni infraestructura.

## Brechas concretas cubiertas

Las ocho pruebas anteriores de VarReadCompatibility comprobaban ejemplos aislados.
Se añadieron tres pruebas locales para verificar combinaciones y recuperación:

1. Una traza sintética que atraviesa los callbacks STOMP y el store reales del
   frontend: dos goles, dos aperturas de revisión, un gol adicional mientras el
   agregado continúa IN_PROGRESS, anulación parcial y continuación final CLEAR.
   Verifica el marcador recibido, referencias, identidad, historial deduplicado,
   rechazo de un frame con revision atrasada y el indicador visible en cada paso.
2. Reconexión durante IN_PROGRESS: recuperación de un snapshot agregado con
   continuación parcial e ignorar un callback CLEAR de la conexión anterior.
3. Lectura de una alerta atrasada por cursor: conserva orden de recepción e
   identidad, aunque sus timestamps sean anteriores a una alerta ya recibida;
   mantiene sus valores de marcador y VAR sin recalcularlos.

Son ejemplos sintéticos de lectura, no un motor de revisiones ni una aprobación
de P2. Las pruebas no cuentan revisiones para producir estado: cada snapshot ya
contiene el var_status que se debe mostrar. Tampoco convierten CLEAR en autorización
de apuestas. No se añadieron campos al protocolo.

## Resultados ejecutados

Con las dependencias y Chrome ya instalados, sin instalaciones ni descargas:

| Comando | Resultado |
| --- | --- |
| `node node_modules/vitest/vitest.mjs run --reporter=dot` | 146 aprobadas en 15 archivos, cero fallos |
| `npm.cmd run build` | TypeScript/Vite correcto, 67 módulos |
| `node node_modules/@playwright/test/cli.js test --config playwright.alerts.config.ts --workers=1 --output test-results/advanced-dev2-20261116-1746/browser --reporter=line` | 14 aprobadas, código 0 |
| `git diff --check -- services/dashboard-frontend` | Sin errores |

Evidencias nuevas bajo `test-results/advanced-dev2-20261116-1746/`, ignoradas por Git.
Las evidencias anteriores se conservaron. El output de una nueva ejecución debe
usar otro directorio porque Playwright limpia el directorio seleccionado.

Playwright ejecutó Chrome en escritorio y móvil con fixtures REST/STOMP
interceptados. Solo se inició Vite temporal en loopback, administrado por el runner,
que lo cerró al terminar. No se iniciaron backends, brokers, bases o migraciones.
Los escenarios incluyen carga, alertas, vacío, errores/reintento, cambio de partido,
recuperación de cursor, teclado, overflow, demo y memory. No certifican CORS real ni E2E.

## Configuración y límites conservados

- `VITE_DASHBOARD_ORIGIN` determina REST y STOMP; un `VITE_WS_URL` explícito conserva
  precedencia. El catálogo durable aporta IDs/nombres y el historial REST.
- `VITE_NOTIFICATION_ORIGIN` es público y opcional; no debe contener credenciales,
  rutas ni secretos. Alertas se consultan solo para selección durable válida.
- Los backends deben autorizar el origen exacto del navegador; localhost y 127.0.0.1
  son orígenes diferentes. Los tests con routing simulado no prueban esa autorización.
- Demo no consulta servicios reales. Memory/selección manual mantiene su comportamiento
  previo y no presupone Notification Backend.
- Los snapshots se protegen por stream_id/revision y las conexiones por vigencia.
  Esto no detecta un ScoreUpdate de origen antiguo que el backend reciba después
  y publique con una revisión local nueva. La garantía de orden de origen sigue
  requiriendo coordinación con DEV 1; no se ordena por timestamp como sustituto.
- El frontend muestra el contenido de alertas entregado por Notification y no
  reescribe mensajes históricos para ajustarlos al estado actual del partido.

La matriz contractual y decisiones pendientes de VAR están en
[Fase 6A](../../dashboard-backend/docs/phase6a-var-v2-proposal.md).
Para la integración real se necesitan contratos/alcance aprobados, recursos aislados,
URLs/puertos y CORS coordinados, artefactos vigentes y eventos reales con identidad
estable. Este trabajo no activa esa integración.
