# Fase 8 — Revisión final DEV 2

Fecha: 2026-11-14, America/Bogota. Base: a3c833f, rama feature/Gabo-dashboard.
El árbol de trabajo estaba limpio al comenzar. No se crearon ramas ni commits.

**Dictamen:** dashboards preparados para revisión e integración local controlada.
La integración definitiva de extremo a extremo sigue pendiente de infraestructura
de DEV 3 y aprobación de contratos con DEV 1. No se declara listo un despliegue
de producción ni validado el flujo completo con brokers reales.

## Arquitectura revisada

El backend mantiene modelos inmutables y puertos de entrada/salida. Dominio y
aplicación no importan Spring ni Jackson. AMQP, lectura HTTP, STOMP y proyección
en memoria se implementan como adaptadores. La composición Spring conecta los
casos de uso por constructor. No hay base de datos, acoplamiento compilado a DEV 1
ni lógica duplicada de goles/VAR: se conserva el marcador recibido.

La proyección compone almacenamiento y publicación en una operación sincronizada
local. No es una transacción entre brokers: aceptar el envío STOMP no confirma
recepción del navegador. Los fallos de publicación se propagan antes de registrar
el evento como procesado, permitiendo los reintentos AMQP.

El frontend separa componentes, páginas, hooks, servicios y tipos. El adaptador
STOMP administra conexiones y suscripciones; SnapshotStore administra revisiones,
recuperación e historial reciente. La demostración está separada del modo live y
no mezcla fixtures con el estado recibido del backend.

## Contratos verificados

Se contrastaron el publicador de match-state-service, el decoder del dashboard,
MatchSnapshotResponse, SnapshotSubscriptionController y los tipos TypeScript.

| Frontera | Contrato real observado |
|---|---|
| DEV 1 → Rabbit | Exchange topic durable live_updates, routing score.{matchId} |
| Cola DEV 2 | dashboard.score-updates, durable, binding score.# |
| JSON Rabbit | match_id, home_score, away_score, event_id, event_type, updated_at, var_status, reference_event_id |
| Navegador | Los mismos campos más stream_id y revision |
| Conexión | WebSocket STOMP nativo /ws |
| Live | SUBSCRIBE /topic/matches/{matchId}/score |
| Recuperación | SUBSCRIBE /app/matches/{matchId}/snapshot |
| REST | GET /api/matches/{matchId}/score; 200 o 404, sin caché |

Las propuestas coinciden con los campos usados actualmente. Los cuatro archivos
de contracts/events continúan vacíos: no hay esquema compartido aprobado. No se
modificaron. No existe catálogo de partidos ni nombres de equipos en ScoreUpdate;
el frontend usa etiquetas externas opcionales o Local/Visitante.

updated_at es tiempo de procesamiento de DEV 1. La ordenación del navegador usa
revision dentro de stream_id, sin reinterpretar updated_at como secuencia global.

## Errores y recuperación

- El decoder AMQP es estricto y separado del mapper HTTP. Rechaza JSON inválido,
  coerciones, campos ausentes, extras y fechas numéricas antes de publicar.
- Un mensaje inválido o conflicto de identidad no se reintenta. Otros fallos
  tienen tres intentos totales; después se rechazan sin reencolar hacia DLX/DLQ.
- La DLQ no tiene ciclo automático de retorno. Su transferencia real depende de
  broker, permisos y política acordada; no está probada con Rabbit real en DEV 2.
- Duplicados por partido/evento no producen otro snapshot; variar solo updated_at
  no modifica el estado. Otro contenido para el mismo ID se rechaza.
- Los errores HTTP de entrada usan ProblemDetail; Spring conserva sus códigos
  habituales. Errores inesperados no exponen mensaje ni stack trace según YAML.
- STOMP bloquea SEND del cliente y wildcard de suscripción. Sesiones desconectadas
  se limpian de forma idempotente. CORS no es autorización por usuario.
- El frontend valida campos y partido esperado, ignora callbacks de sesiones viejas,
  revisiones atrasadas y eventos repetidos. Recupera estado en cada conexión;
  retiene el último dato identificado como desactualizado durante una desconexión.
- Ocho fallos consecutivos sin recuperación detienen la reconexión automática;
  hay timeout de 8 segundos y reconexión con espera exponencial de 1 a 30 segundos.

## Variables y revisión de credenciales

Backend: RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_USERNAME, RABBITMQ_PASSWORD,
RABBITMQ_EXCHANGE, DASHBOARD_PORT, DASHBOARD_RABBIT_LISTENER_ENABLED y
DASHBOARD_ALLOWED_ORIGINS. El perfil prod exige orígenes explícitos válidos,
pero no obliga por código a sustituir guest: suministrar credenciales aprobadas
externamente es requisito operativo pendiente antes de desplegar.

Frontend: VITE_WS_URL, VITE_MATCH_IDS y VITE_MATCH_METADATA. Se incorporan al
bundle y no deben contener secretos. Para HTTPS se necesita wss y el origen del
frontend debe estar autorizado por el backend. Preview 4173 requiere autorización
explícita, porque el desarrollo por defecto solo permite localhost/127.0.0.1:5173.

Se inspeccionaron las fuentes/configuraciones versionadas de ambos dashboards y
la plantilla nueva. Se buscaron claves privadas PEM, patrones de tokens GitHub,
claves AWS, JWT y URLs con usuario/contraseña, además de revisar asignaciones de
password/secret/token y archivos de entorno versionados. No se encontraron
indicios de credenciales reales. guest es un valor público de desarrollo; los
REPLACE_* de la plantilla nueva son marcadores que el operador debe reemplazar.

La única plantilla de entorno previamente versionada era la del frontend. No se
encontraron .env locales con secretos en las carpetas de ambos dashboards. La
revisión confirma que .env y .env.local están ignorados y que las plantillas son
versionables. No se auditó todo el historial Git ni fuentes ajenas a DEV 2.

Se añadió dashboard-backend/.env.example como documentación. Spring Boot no lo
carga automáticamente: exportar variables al proceso o usar la configuración
externa autorizada. Vite sí carga .env.local. No se añadieron cargadores ni se
cambió configuración de ejecución.

## Resultados ejecutados en esta fase

| Verificación | Resultado observado |
|---|---|
| Maven dashboard-backend clean verify | BUILD SUCCESS; 84 pruebas, 0 fallos, 0 errores, 0 omitidas |
| npm test | 7 archivos; 50 pruebas aprobadas |
| npm run build | TypeScript estricto y Vite correctos; dist generado |
| Chrome escritorio/móvil | 6 aprobadas, 2 omitidas explícitamente, 0 fallos |
| Artefactos | JAR ejecutable y bundle frontend generados |

Herramientas: Java 17.0.21, Maven 3.9.16, Node 24.21.0, npm 12.2.0.
Backend Boot 3.5.7; frontend React 19.3.1, Vite 7.3.8, STOMP.js 7.5.0.

La suite Chrome se ejecutó con un worker, REAL_BACKEND_RESTART=1,
LIVE_BACKEND_SMOKE=0 y VITE_WS_URL=ws://127.0.0.1:18081/ws. Sus dos pruebas de
reinicio crearon exclusivamente procesos de backend aislados, sin Rabbit, y
verificaron conexión/reconexión y snapshot no disponible. Las otras cuatro
verificaron demo y eventos sobre transporte STOMP simulado en escritorio/móvil.
Los dos smoke tests que exigen una instancia ya iniciada se omitieron por su
condición de entorno; no son fallos ni se contabilizan como aprobados.

No se volvieron a ejecutar pruebas de otros microservicios en esta fase: no hubo
cambios en ellos. Los resultados completos previos están en Fase 7. Tampoco se
iniciaron brokers: se mantiene la instrucción del usuario de esperar a DEV 3.

## Ejecución local documentada

Desde la raíz con Java 17/Maven y conexión Rabbit autorizada configurada:

```powershell
mvn -f services/dashboard-backend/pom.xml clean verify
java -jar services/dashboard-backend/target/dashboard-backend-0.0.1-SNAPSHOT.jar
```

En dashboard-frontend, configurar .env.local a partir de .env.example y ejecutar:

```powershell
npm.cmd ci
npm.cmd run dev
```

La comprobación sin broker usa los tres overrides documentados en el README del
backend para desactivar listener, RabbitAdmin e indicador de salud Rabbit. Solo
valida HTTP/STOMP local; no sustituye el arranque normal del servicio integrado.
No se introdujeron scripts que inicien o alteren Docker.

## Dependencias y limitaciones para la integración definitiva

1. DEV 3 debe exponer y confirmar puertos/hosts de Kafka y RabbitMQ, credenciales,
   permisos, topología, políticas DLQ y retención. Compose compartido sigue intacto.
2. DEV 1 debe acordar contratos, catálogo/identidad de equipos, fuente de snapshots,
   orden y replay. El productor no tiene endpoint de simulación implementado.
3. El estado/outbox de DEV 1 y la proyección/deduplicación de DEV 2 son volátiles.
   Reiniciar puede perder marcadores; cola durable no repone mensajes ya ACK.
4. La proyección no soporta múltiples réplicas con la cola compartida actual.
5. El backend limita a 10000 eventos globales; saturación envía nuevos mensajes
   a DLQ tras retries. El frontend conserva solo veinte eventos visibles y hasta
   10000 identidades por partido. No es un archivo histórico.
6. No hay autenticación/autorización por usuario. TLS, credenciales no predeterminadas
   y controles de acceso de despliegue deben resolverse antes de exposición externa.
7. La revisión local no ordena replays de origen. No se promete exactly-once.
8. El selector frontend admite hasta veinte IDs ASCII seguros; no implementa un
   catálogo ni soporta todos los identificadores arbitrarios aceptados por Java.
9. Los tipos TypeScript rechazan números fuera del rango entero seguro de JavaScript.

## Cambios autorizados y evidencias

Cambios de esta fase: README de backend, README de frontend, plantilla backend
.env.example y este informe. No se cambió Java, TypeScript, dependencias, POM,
Docker Compose, contratos ni otros servicios. Las pruebas solo regeneraron target,
dist y test-results, ignorados por Git. No hubo commits, push, merges o resets.

Además, la comparación 43aab3e..a3c833f confirmó que el trabajo previo de DEV 2
solo afecta dashboard-backend y dashboard-frontend. Esa comparación no atribuye
al agente los commits hechos previamente por el usuario.

Evidencias locales de esta fase en
[dashboard-backend/target/phase8-evidence](../../dashboard-backend/target/phase8-evidence):
backend-verify.log, frontend-unit.log, frontend-build.log, browser-verify.log,
review-summary.json, capturas y logs de las instancias aisladas. Los logs npm
pueden incluir avisos NativeCommandError de PowerShell por stderr; los procesos
de verificación terminaron con código 0 y se contrastaron los resúmenes finales.

Antes de clean verify se preservó phase7-evidence fuera de target y se restauró
después. Un clean posterior elimina evidencias locales: conservarlas antes si
se necesitan para la aprobación. Los informes versionados permanecen disponibles.

**Pendiente de aprobación del usuario.** No se continúa con nuevas funcionalidades
ni con cambios de infraestructura desde esta revisión.
