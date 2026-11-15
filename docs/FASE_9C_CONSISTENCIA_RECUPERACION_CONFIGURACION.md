# Fase 9C — Consistencia, recuperación y configuración

Fecha: 15 de noviembre de 2026. Rama: `fix/auditoria-integral-fase9`.
Alcance exclusivo: M01, M03, M06 y M07. No se inicia 9D.

## Git inicial y preservación de 9A/9B

El estado inicial contenía 16 archivos modificados y 15 nuevos de 9A/9B,
sin staging. Se conservaron sus cambios; no se cambiaron rama, contratos
compartidos, modelos ni esqueletos DEV 3. No se instalaron dependencias.

## M01 — Una sentencia, un snapshot

Antes, `ScoreProjection.view()` leía `dashboard.matches` y después
`dashboard.inbox` mediante dos sentencias. No había transacción de lectura
ni aislamiento configurado explícitamente para esas consultas. El aislamiento
DEFAULT de las escrituras no convertía esas dos lecturas en un snapshot común.

Ahora una única sentencia obtiene `row_to_json(m)` y el historial ordenado
mediante una subconsulta correlacionada. En PostgreSQL, ambas partes comparten
el snapshot MVCC de la sentencia, incluso con READ COMMITTED. No se depende de
una anotación read-only ni se agregan bloqueos FOR UPDATE para lectores.
El escritor existente sigue confirmando inbox y marcador en una transacción.

El contrato REST no cambia: campos del partido, active_goals, últimos 30 events
y ausencia del partido como NoSuchElementException/404. Los null y marcadores
iniciales se conservan. Los endpoints de snapshots STOMP/REST siguen disponibles.

`ConsistentProjectionReadTest` usa dos hilos y barreras: captura una imagen de
sentencia, permite un commit concurrente antes de consumir el resultado y exige
que el marcador/historial sean íntegramente anteriores. Una nueva lectura ve la
imagen posterior. También comprueba historial vacío y partido inexistente.

Es una simulación JDBC del snapshot de sentencia y una verificación de que el
código realiza una sola consulta; no una prueba del motor PostgreSQL. No se
consultó SHOW transaction_isolation ni se certifica el ajuste de un servidor
activo. La solución no requiere que ese default sea REPEATABLE READ. La prueba
real del SQL y su concurrencia en PostgreSQL queda para infraestructura autorizada.

La lectura aún reconstruye el historial completo; M02/rendimiento pertenece a
9D y no se presenta como resuelto aquí.

## M03 — Recuperación controlada del catálogo

`loading.js` coordina exclusivamente GET:

- Estados visibles de carga, error, catálogo vacío y disponibilidad; botón para
  volver a consultar sin recargar la página.
- Reintento automático del catálogo con espera 1, 2, 4, 8, 16 y máximo 30 segundos.
  Un catálogo vacío se vuelve a consultar tras cinco segundos.
- Timeout de ocho segundos, AbortController y generaciones de solicitudes.
  Respuestas antiguas no reemplazan catálogo o partido nuevos aunque el
  transporte simulado ignore la cancelación.
- Poll del partido un segundo después de completar la lectura anterior, sin
  setInterval ni polls superpuestos. Los fallos también usan backoff acotado.
- Navegar a otro partido cancela el GET anterior; controles de publicación
  permanecen bloqueados hasta tener datos del partido seleccionado.
- pagehide cancela solicitudes/timers; pageshow desde bfcache reinicia lecturas.

Se comunicó antes de modificar app.js que el cambio se limita a orquestación de
cargas. `operations.js` y el cuerpo pendiente de H03 no cambian. Recuperar un
catálogo elige el partido pendiente si existe, no genera UUID, no publica y no
elimina el pendiente. Se vuelve a consultar la capacidad del simulador tras
recuperar el catálogo; los errores de esa consulta dejan escritura bloqueada.

Las pruebas cubren fallo inicial, recuperación automática, backoff, respuestas
tardías de catálogo/partido, navegación, timeout, cancelación, ausencia de polls
simultáneos y preservación byte a byte del pendiente al recuperar el catálogo.
Se ejecutan scripts/handlers reales con DOM y HTTP simulados; no acreditan
navegador conectado a servicios reales.

## M06 — Modos separados sin defaults contradictorios

Referencia de configuración y arranque: [MODOS_EJECUCION.md](MODOS_EJECUCION.md).

- Ajustes comunes del dashboard consolidados en application.properties. YAML
  conserva los namespaces propios y el perfil memory sus overrides específicos.
- Durable host: puerto 8080, AMQP 5673. Memory: 8081, AMQP 5672, sin JDBC/Flyway.
- DASHBOARD_PORT precede a PORT dentro de la expresión; las fuentes externas de
  server.port conservan la precedencia normal de Spring.
- Compose fija PORT=8080 para ambos contenedores. No se cambian mappings del
  host 18080/18081, puertos de brokers/DB, volúmenes ni credenciales.
- SIMULATOR_WRITE_ENABLED permanece false por defecto y true explícito en el
  anchor local compartido de 9B. No cambia su semántica ni se añade autenticación.
- React mantiene VITE_WS_URL como override y el fallback memory de 8081. Puede
  derivar /ws de VITE_DASHBOARD_ORIGIN si no se define VITE_WS_URL. Se documentan
  orígenes 18080/8080/8081 y la necesidad de indicar el MATCH_ID real.
- Plantillas actualizadas sin cambiar valores de credenciales. El topic utiliza
  MATCH_EVENTS_TOPIC, que es el nombre que realmente leen los servicios.

ExecutionModesTest carga configuración real de Boot sin crear beans de conexión;
prueba modos, aliases, escritura y configuración externa de timeouts.
LocalMvpConfigurationTest inspecciona el YAML: ambos servicios usan 8080, reciben
la bandera local y todos los puertos publicados siguen siendo loopback.
Los puertos que espera start-mvp.sh no cambian; no se ejecutó el script porque
compila artefactos e inicia infraestructura.

## M07 — Límites HTTP explícitos

ProducerGateway configura SimpleClientHttpRequestFactory sin dependencias nuevas:

| Ajuste | Variable | Default |
|---|---|---|
| Conexión | PRODUCER_CONNECT_TIMEOUT | 2s |
| Lectura/espera de respuesta | PRODUCER_READ_TIMEOUT | 5s |

Se rechazan valores menores de 1 ms o fuera del rango int en milisegundos.
El timeout de lectura cubre esperas de headers y cuerpo; es un límite de espera
por lectura, no un deadline total para respuestas que envían fragmentos sin parar.
No se certifica un límite del DNS del sistema mediante estas pruebas.

No se añaden retries de aplicación ni se modifican eventId, timestamp o cuerpo.
Las respuestas del productor conservan su estado HTTP: rechazos conocidos se
describen como rechazo; los demás estados mantienen incertidumbre. Errores de
transporte siguen siendo 503. La capa H03 conserva la identidad para un reintento
explícito. No se cambia el significado de 202 ni la deduplicación de DEV 1.

ProducerGatewayTimeoutTest utiliza un stub HTTP de loopback en el JVM de test,
con puerto efímero y ciclo de vida propio. Simula headers lentos, cuerpo lento,
respuesta incierta, rechazo 400 y reintento explícito con cuerpo idéntico. No
arranca un productor, broker, base de datos o contenedor reales. La configuración
del connect timeout se verifica sin provocar conexiones a destinos externos.

## Pruebas ejecutadas

Se probaron las correcciones focalizadas y después la regresión local completa.
No se suman las repeticiones focalizadas al recuento final:

| Componente | Pruebas | Fallos | Errores | Skipped |
|---|---:|---:|---:|---:|
| Dashboard Java | 115 | 0 | 0 | 0 |
| Productor Java | 57 | 0 | 0 | 0 |
| Estado Java | 81 | 0 | 0 | 0 |
| Frontend React/Vitest | 58 | 0 | 0 | 0 |
| Scripts estáticos/Node | 23 | 0 | 0 | 0 |
| **Total** | **334** | **0** | **0** | **0** |

Los tres Maven test terminaron BUILD SUCCESS. Se usó modo offline con la caché
existente. React usó npm test, sin npm install/ci. Node usa su runner incorporado.
Los logs Maven/Vitest están en target/phase9c-test.log, ignorados por Git.

```powershell
mvn -o "-Dmaven.repo.local=$PWD/.m2/repository" -f services/dashboard-backend/pom.xml '-Dtest=!DurableSnapshotIntegrationTest' test
mvn -o "-Dmaven.repo.local=$PWD/.m2/repository" -f services/live-feed-producer/pom.xml test
mvn -o "-Dmaven.repo.local=$PWD/.m2/repository" -f services/match-state-service/pom.xml '-Dtest=!*IntegrationTest' test
npm.cmd test --prefix services/dashboard-frontend
node --test services/dashboard-backend/src/test/js/operations.test.cjs services/dashboard-backend/src/test/js/app.test.cjs services/dashboard-backend/src/test/js/loading.test.cjs
```

Omisiones explícitas: cinco clases PostgreSQL/brokers de estado y la clase
DurableSnapshotIntegrationTest del dashboard. No se cuentan como ejecutadas ni
como skipped del selector. No hubo pruebas con Kafka/Rabbit/PostgreSQL reales.
Las pruebas WebSocket locales existentes usan memory con Rabbit deshabilitado.
Las regresiones H01–H04 siguen incluidas y aprobadas.

## Empaquetado y Git

La inspección de lectura exclusiva volvió a devolver error Windows 32 (sharing
violation) en los tres JAR. Ninguno tiene atributo ReadOnly y no existen los
destinos .jar.original. No se borraron JAR ni se detuvieron procesos.
No se ejecutó verify/repackage sobre esos artefactos en uso: no se declara un
empaquetado ejecutable nuevo ni build completo exitoso. Maven test sí pasó.

El estado final acumula 27 archivos modificados y 22 nuevos de 9A–9C, sin staging.
git diff --check pasó. No se cambiaron rama, credenciales, contratos o esqueletos
DEV 3; no hubo commits, push o merges.

## Archivos tocados exclusivamente en la etapa 9C

Inventario respecto al estado inicial de 9C; algunos ya tenían cambios de 9A/9B:

```text
.env.example
README.md
docker-compose.yml
docs/MODOS_EJECUCION.md
docs/FASE_9C_CONSISTENCIA_RECUPERACION_CONFIGURACION.md
services/dashboard-backend/.env.example
services/dashboard-backend/README.md
services/dashboard-backend/src/main/java/com/live_jgv/dashboard/ScoreProjection.java
services/dashboard-backend/src/main/java/com/live_jgv/dashboard/ProducerGateway.java
services/dashboard-backend/src/main/resources/application.properties
services/dashboard-backend/src/main/resources/application.yml
services/dashboard-backend/src/main/resources/application-memory.properties
services/dashboard-backend/src/main/resources/static/index.html
services/dashboard-backend/src/main/resources/static/app.js
services/dashboard-backend/src/main/resources/static/loading.js
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/ConsistentProjectionReadTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/ExecutionModesTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/LocalMvpConfigurationTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/ProducerGatewayTimeoutTest.java
services/dashboard-backend/src/test/js/app.test.cjs
services/dashboard-backend/src/test/js/loading.test.cjs
services/dashboard-frontend/.env.example
services/dashboard-frontend/README.md
services/dashboard-frontend/src/services/liveConfig.ts
services/dashboard-frontend/src/services/liveConfig.test.ts
```

## Riesgos y aprobaciones pendientes

M01, M03 y M06 quedan corregidos en código/pruebas locales; M07 tiene límites de
espera explícitos y preserva la incertidumbre. La prueba real de MVCC/SQL,
mensajería, despliegue y comportamiento del sistema completo requiere recursos
aislados y autorización independiente. El empaquetado requiere acordar liberación
de artefactos o una salida aislada; no se fuerza ninguna de esas acciones.

No se requieren migraciones o decisiones nuevas de infraestructura compartida
para estos cambios. Permanecen pendientes de 9D rendimiento de historiales,
coordinación distribuida de outbox, réplicas STOMP y épocas de streams; no se han
implementado aquí. Se espera autorización antes de continuar.
