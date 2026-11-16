# Fase 6A — Criterios de aceptación Dashboard y Notification

**Criterios propuestos, sin ejecución de infraestructura en esta fase.**
Contratos C01..C08: [inventario](phase6a-contract-matrix.md).
Bloqueos D01..D10: [decisiones](phase6a-decisions-and-git.md).

L = verificable con pruebas locales/dobles; R = requiere PostgreSQL/broker/servicios
reales aislados; B = depende de decisión. «Cubierto localmente» no convierte R en
aprobado. Todos los escenarios R de este documento permanecen pendientes.

## 1. Matriz de criterios y evidencia

| ID / ámbito | Estímulo y resultado verificable | Evidencia / condición de fallo | Nivel |
| --- | --- | --- | --- |
| DUP01 / ambos backends | Dos entregas idénticas: una fila inbox y una alerta como máximo; dashboard no incrementa revisión por duplicado y puede republicar su último snapshot | SQL antes/después, IDs, revisiones y redelivery; falla si hay segundo efecto de negocio | L + R |
| CONFLICT01 / ambos | Mismo match/event ID con otro contenido: rechazar sin sobrescribir proyección/alerta original; aplicar política de error/DLQ vigente | Payload original conservado, transacción, DLQ/ACK; falla si se corrige silenciosamente | L + R; igualdad exacta D03 |
| ORDER01 / frontend | Frames del mismo stream entregados en orden inverso de revision no hacen retroceder snapshot; respuesta de partido/origen anterior no modifica selección actual | Transporte controlado, estado visible y pruebas de A-B-A | L; R con red autorizada |
| ORDER02 / Rabbit→dashboard | Publicación y recuperación conservan el orden autoritativo por partido; una actualización vieja no debe sustituir estado nuevo por llegar más tarde | Secuencia de origen/outbox frente a recepción; actualmente no existe revisión de origen en C02 | B/R, D04 |
| REST01 / dashboard | Catálogo, detalle y snapshot coinciden en identidad/último evento; transición entre consultas se reintenta de forma acotada; histórico <=30 no elimina active_goals antiguos | REST, secuencia inbox, tests de lectura consistente y ventana; no inventar paginación | L + R |
| WS01 / dashboard/React | Conexión/suscripción por partido y snapshot directo correcto; desconexión/reconexión recupera marcador sin mezclar stream/partido; sin datos ofrece unavailable | Frames reales, REST y captura de UI; falla si usa snapshot de otra época/partido | L + R |
| PERSIST01 / dashboard | Reinicio ordinario conserva proyección, inbox, época y revisión; la UI recupera mediante REST/STOMP | SQL y stream_id/revision antes/después; no confundir con memory volátil | R; soporte L anterior |
| PERSIST02 / notification | Inbox y alerta se confirman atómicamente; fallo entre insert y alerta revierte ambas y contador; reinicio conserva alert_id y páginas | NotificationPostgresIT, SQL, prueba de nueva JVM; falla con escritura parcial | L + R |
| CURSOR01 / notification/React | Incrementales ordenados por secuencia comprometida, sin saltar transacción pendiente; has_more drena; página vacía puede avanzar cursor; duplicados visuales ausentes | Dos transacciones concurrentes, respuestas REST, cursor opaco e IDs | L + R |
| CURSOR02 / épocas | Cursor de otro partido inválido→400; época distinta/checkpoint adelantado→409; UI recupera ventana inicial sin bucle ni mezcla | Respuestas y UI; la rotación/restauración necesita aprobación, no es automática | L + R/B, D03 |
| ALERT01 / eventos | GOAL, VAR_CHECK y GOAL_ANNULLED generan una alerta; MATCH_CONTINUES registra SUPPRESSED sin visible; scores proceden de C02 | Inbox/outcome, alerta, API y UI; ausencia de jugadores/minutos inferidos | L + R |
| VAR01 / estado agregado | Anulación o continuación con IN_PROGRESS mantiene revisión visible; CLEAR no activa procesos externos | Fixtures de lectura y UI; escenarios reales P2 dependen del motor aprobado | L + B/R, D02/D09 |
| FAIL01 / notification | Error transitorio: reintentos acotados; inválido/permanente: tratamiento controlado; desconocido no se descarta silenciosamente | Intentos y categoría; sin bucle de requeue continuo | L + R |
| FAIL02 / ACK/DLQ | ACK original solo tras commit o transferencia persistente confirmada a DLQ; confirm/return fallido conserva original, NACK/requeue y detiene consumidor | Confirm, return, ACK/NACK, cola y SQL; no basta contar DLQ | L + R |
| HTTP01 / gateway/estático | 500/503 reconocidos mantienen status, code seguro y event válido; mismatch no reemplaza identidad; timeout/cuerpo perdido siguen pendientes | MockMvc real controlador/advice + HTTP simulado; después transporte real aislado | L + R |
| HTTP02 / reenvío manual | Body guardado antes del POST, UUID/timestamp/referencia inmutables, Web Locks y recarga; consulta fallida no dispara POST; processed=true evita otro POST | Storage, cuerpos enviados, conteo peticiones y feedback sin secretos | L + R |
| READY01 / notification | Iniciado no implica READY: storage, PostgreSQL, consumidor habilitado/operativo y consume-ok deben estar disponibles | Actuator y test con dobles; fallo real de DB/broker solo en entorno autorizado | L + R |
| MODES01 / React | Demo no abre servicios reales; memory/manual no presupone Notification; durable requiere origen válido y catálogo; cancelación y reintento no afectan STOMP | Vitest/Playwright, peticiones y botones accesibles; CORS real por separado | L + R |
| LEGACY01 / V1 | Mantener fixtures/bytes e interpretación V1; reconstrucción no crea outbox nueva ni reescribe pasado | Comparación de IDs/payloads e historial antes/después; no activar V2 implícitamente | L + B/R |
| ISO01 / laboratorio | Grupos/colas independientes; cero acceso/escritura/limpieza fuera de recursos del manifiesto | Configuración efectiva saneada, labels, permisos, inventario y hashes | R, D05..D08 |
| ARCH01 / compatibilidad | Archiver y Match State reciben copias C01 por grupos distintos; archivo no se usa como confirmación de aplicación deportiva | Topic/key/payload original, recibos y outcomes por servicio | R coordinado DEV 1/3 |

## 2. Límites que impiden dar criterios por aprobados

ORDER02 no está resuelto por revision de dashboard: una entrega nueva con event_id
antiguo y marcador atrasado recibiría una revisión local mayor. El código aplica
ScoreUpdate por recepción; no incorpora orden autoritativo de origen. Debe acordarse
publicación/reentrega ordenada por partido o una evolución contractual explícita.
No ordenar por timestamp como sustituto, ni añadir campos en esta fase.

CURSOR02 no resuelve identidad de negocio: girar stream_epoch/cursor epoch no cambia
las claves del inbox. Restaurar y reutilizar IDs puede producir duplicados o
conflictos irreparables sin una política aprobada. Un checkpoint reiniciado no
garantiza recuperar alertas borradas por una restauración.

ALERT01 exige consistencia con el evento que originó la alerta, no igualdad de todas
las alertas históricas con el marcador actual. VAR01 necesita revisar «Marcador
confirmado» cuando IN_PROGRESS; la presentación actual muestra el contenido del
backend y no lo corrige por su cuenta. No crear una alerta MATCH_CONTINUES aquí.

HTTP01/02 conservan incertidumbre: 202 no confirma procesamiento, processed=false
no prueba rechazo Kafka y recuperar un body de error no confirma publicación.
Reenviar idéntico puede duplicar registros Kafka; no se exige exactly-once físico.

## 3. Evidencia local anterior, no nueva certificación de Fase 6A

| Ejecución anterior | Resultado conservado | Alcance |
| --- | --- | --- |
| Fase 5D dashboard verify | 150 aprobadas, BUILD SUCCESS | Java local; fuentes DEV 1 reales con transporte/datos controlados |
| Fase 5D notification verify | 103 aprobadas, BUILD SUCCESS | Java local; sin DB/broker reales |
| Fase 5D Node / Vitest / build | 34 / 143 aprobadas; build correcto | JS/React y dobles |
| Fase 5D Playwright | 14 aprobadas | Chrome escritorio/móvil con fixtures y Vite temporal |
| Fase 5E | 34 Java seleccionadas (31 gateway + 3 compatibilidad), 34 Node y 143 Vitest aprobadas | No suma a los totales anteriores ni sustituye E2E |

Reportes previos: `services/dashboard-backend/target/phase5d/` y `phase5e/`,
`services/notification-backend/target/phase5d/`,
`services/dashboard-frontend/test-results/phase5d/` y `phase5e/`.
Pueden no viajar con Git porque son artefactos locales ignorados.
En Fase 6A solo se inspeccionó código/documentación y se validó el diff documental.

## 4. Acta futura de aceptación

Por criterio registrar: ID, commit/JAR/migraciones, run_id/recursos aprobados,
entrada exacta/IDs, esperado, observado, evidencia, duración, estado
APROBADO/FALLIDO/BLOQUEADO y responsable. No convertir OMITIDO/BLOQUEADO en aprobado.
Un fallo de infraestructura no autoriza omitir una garantía crítica. El responsable
del cambio corrige en su módulo/rama; el equipo vuelve a aprobar antes de E2E.
