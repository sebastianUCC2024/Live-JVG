# Fase 9B — Idempotencia y simulador local explícito

Fecha: 15 de noviembre de 2026. Rama: `fix/auditoria-integral-fase9`.
Alcance: H03 y H04, alternativa B autorizada. No se inicia 9C.

## Estado inicial y conservación de 9A

Se encontraron los 15 archivos de 9A pendientes: nueve modificados y seis nuevos.
No se revirtieron. Se mantienen retry/DLQ confirmada, clasificación permanente,
cuarentena y validación de nuevas referencias, sin reinterpretar el historial.
Los modelos, schemas compartidos y esqueletos DEV 3 permanecen sin cambios.

## H03 — Identidad de la operación

El flujo sigue siendo interfaz estática → DashboardController → ProducerGateway
→ MatchSimulationController → Kafka. El gateway no crea ni cambia la identidad.

`operations.js` implementa el ciclo de una operación:

1. Genera UUID y timestamp una sola vez. Serializa el cuerpo completo y lo guarda
   en localStorage **antes de cualquier POST**. Si no puede persistir, no publica.
2. Conserva el mismo string JSON al reintentar, incluidos minuto, equipo y
   referencia. No reconstruye el evento a partir de controles modificados.
3. Bloquea nuevas jugadas mientras exista una operación, incluso después de
   recargar. Web Locks coordina publicaciones, consultas y descarte entre pestañas
   del mismo origen; el bloqueo en memoria también evita el doble clic.
4. Un timeout de diez segundos, un fallo de red, respuesta perdida, 5xx o una
   respuesta exitosa sin la identidad esperada deja resultado incierto. El plazo
   cubre también la lectura del cuerpo HTTP. Abortar fetch no cancela Kafka.
5. Una primera respuesta de rechazo 400/401/403/404/405/415/422 se registra como
   rechazo definitivo. No se elimina automáticamente. Si hubo incertidumbre
   anterior, un rechazo del reintento no borra esa incertidumbre.
6. Una respuesta 202 válida conserva la operación hasta observar procesamiento.
   No se confunde confirmación Kafka con recepción en el marcador.
7. Antes del reintento manual consulta
   `GET /api/matches/{id}/events/{eventId}`. Su campo `processed` indica presencia
   en el inbox durable, sin depender de los últimos 30 eventos de la interfaz.
   `false` **no significa rechazo**: el evento puede seguir en tránsito.
8. Si está procesada, libera la operación sin otro POST. Si no, el usuario puede
   reenviar el mismo cuerpo. Una consulta fallida conserva el pendiente y no
   habilita una nueva publicación automática.
9. La recarga solo restaura y consulta; nunca genera ni publica un reemplazo.
10. «Olvidar operación» exige confirmación explícita: advierte que no cancela el
    evento y que una nueva identidad podría duplicar la jugada. El descarte no
    genera un evento. Registros locales ilegibles se conservan hasta esa decisión.

La consulta nueva usa parámetros JDBC y el índice único existente
`(match_id,event_id)`; no requiere migración ni cambio de contrato de mensajería.

### Semántica HTTP preservada

Con escritura habilitada, los POST conservan 202 después del ACK Kafka, 400 por
validación y 503 por fallo de publicación/transporte. No se redefine una respuesta
fallida como garantía de que Kafka rechazó el evento. La capa de interfaz registra
su incertidumbre localmente; no se introduce una promesa exactly-once.

## H04 — Alternativa B, exclusivamente MVP local

En productor y dashboard:

```text
simulator.write-enabled=${SIMULATOR_WRITE_ENABLED:false}
```

Sin habilitación explícita, el POST de publicación devuelve 403 antes de invocar
la lógica de publicación. Esto también se aplica a clientes sin Origin: CORS no
es la barrera de escritura. Las lecturas permanecen disponibles.

Compose habilita ambos servicios mediante el mismo anchor `x-simulator`, con
`SIMULATOR_WRITE_ENABLED: "true"`. Todos los puertos publicados siguen ligados
a `127.0.0.1`; no se cambian sus números. La interfaz consulta
`GET /api/simulator/config`, y bloquea controles de publicación cuando la
escritura está deshabilitada o no puede confirmar la configuración.

No se añade autenticación. La bandera habilitada autoriza el simulador en una
red local confiable; no identifica usuarios. Esta configuración **no es segura
para exposición pública** ni debe trasladarse a un reverse proxy público sin
una decisión y controles adicionales. No se han desplegado cambios de Compose
en contenedores existentes durante esta fase.

## Pruebas locales ejecutadas

| Componente | Objetivo | Pruebas | Fallos | Errores | Skipped |
|---|---|---:|---:|---:|---:|
| live-feed-producer | Maven test | 57 | 0 | 0 | 0 |
| dashboard-backend | Maven test | 105 | 0 | 0 | 0 |
| match-state-service | Maven test, regresión 9A | 81 | 0 | 0 | 0 |
| Scripts estáticos | Node test runner | 16 | 0 | 0 | 0 |
| Total | | 259 | 0 | 0 | 0 |

Los tres `mvn test` terminaron en BUILD SUCCESS. Se usó modo offline y la caché
Maven ya existente, sin instalar dependencias ni modificar POM/package.json.

Comandos, desde la raíz:

```powershell
mvn -o "-Dmaven.repo.local=$PWD/.m2/repository" -f services/live-feed-producer/pom.xml test
mvn -o "-Dmaven.repo.local=$PWD/.m2/repository" -f services/dashboard-backend/pom.xml '-Dtest=!DurableSnapshotIntegrationTest' test
mvn -o "-Dmaven.repo.local=$PWD/.m2/repository" -f services/match-state-service/pom.xml '-Dtest=!*IntegrationTest' test
node --test services/dashboard-backend/src/test/js/operations.test.cjs services/dashboard-backend/src/test/js/app.test.cjs
```

La selección excluye explícitamente las cinco clases de integración real de
estado y la clase PostgreSQL durable del dashboard; no se cuentan como ejecutadas
ni como skipped de Surefire. Las pruebas WebSocket locales existentes usan
perfil memory y Rabbit deshabilitado. No se arranca ningún broker o contenedor.

Cobertura nueva:

- Node: éxito, persistencia antes del envío, timeout de conexión/cuerpo, pérdida
  de respuesta, reintento con cuerpo idéntico, doble clic, pestañas concurrentes,
  recarga, rechazo definitivo, incertidumbre previa, operación ya procesada,
  almacenamiento indisponible/corrupto y descarte explícito.
- Harness de DOM: ejecuta los scripts y handlers reales de los botones con HTTP
  simulado; habilitación, solo lectura, doble clic y restauración al recargar.
  No se presenta como validación en navegador real.
- MockMvc: escritura habilitada/deshabilitada en ambos servicios, rechazo por
  defecto en perfil prod, lectura disponible, preservación de UUID/timestamp,
  espera de ACK y regresión de validación H02.
- Gateway con MockRestServiceServer: respuesta perdida seguida de reintento con
  el mismo contenido; no se contacta ningún productor real.
- Compose: lectura estática de YAML, habilitación coherente y publicación de
  todos los puertos solo en loopback; no se ejecuta Docker.

El primer intento del dashboard falló al compilar un stub Mockito con retorno
Map<?,?>; se corrigió el stub. El resultado final de esa suite es 105/0/0.
La regresión vuelve a incluir las pruebas H01/H02 de 9A.

## Maven verify y repackage — resultado separado

Los intentos `verify` de 9A pasaron sus tests y fallaron en repackage al renombrar
los JAR a `.jar.original`. En 9B se inspeccionaron los tres artefactos sin
modificarlos: no tienen atributo ReadOnly, no existe el destino `.jar.original`,
y abrir los JAR originales con acceso de lectura exclusivo devuelve IOException
con HRESULT `-2147024864`, código Windows **32 (sharing violation)**.

Hay procesos Java anteriores en ejecución, pero no se atribuye cada handle a
un PID sin una comprobación específica. No se detuvo ningún proceso, borró
artefacto ni intentó forzar la liberación. La causa inmediata observada es el
uso compartido incompatible del archivo; no un test fallido.

No se reejecutó `verify` sobre esos artefactos en uso para evitar reescribirlos.
Por tanto, **Maven test pasó; Maven verify de 9B y el nuevo empaquetado ejecutable
quedan pendientes**. No se declara build completo exitoso ni despliegue actualizado.

## Riesgos residuales y validación pendiente

- localStorage y Web Locks son por origen/navegador. Cambiar hostname/puerto,
  borrar datos, otro dispositivo o perder el perfil no recupera ese pendiente.
- Se necesita un navegador que soporte Web Locks en contexto seguro, como
  localhost. Si no está disponible, no se publica; no se usa un lock inseguro
  basado en localStorage como sustituto.
- Las publicaciones pueden repetirse con la misma identidad. La deduplicación
  existente de DEV 1 sigue siendo necesaria; no se promete exactamente una vez.
- Tras una aceptación, el evento puede quedar en cuarentena o no llegar al
  dashboard. El pendiente no se elimina automáticamente por tiempo transcurrido.
- El lookup refleja el inbox del dashboard, no una transacción distribuida ni
  un servicio nuevo de autorización. Un true confirma observación previa; un
  false no prueba ausencia en Kafka.
- Descartar explícitamente un pendiente permite una nueva jugada y puede generar
  duplicación semántica si el anterior se procesa después; la advertencia lo indica.
- La comprobación de botones usa DOM/HTTP simulados. Requieren autorización
  separada la prueba en navegador con infraestructura real, el despliegue de
  Compose actualizado y el empaquetado cuando los artefactos estén disponibles
  o se acuerde una salida de compilación aislada.

## Archivos de 9B

Respecto al estado inicial que ya contenía 9A:

```text
docker-compose.yml
docs/FASE_9B_IDEMPOTENCIA_SEGURIDAD.md
services/dashboard-backend/src/main/java/com/live_jgv/dashboard/DashboardController.java
services/dashboard-backend/src/main/java/com/live_jgv/dashboard/ScoreProjection.java
services/dashboard-backend/src/main/resources/application.properties
services/dashboard-backend/src/main/resources/static/app.js
services/dashboard-backend/src/main/resources/static/index.html
services/dashboard-backend/src/main/resources/static/operations.js
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/DashboardBackendApplicationTests.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/SimulatorDisabledTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/LocalMvpConfigurationTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/ProducerGatewayIdentityTest.java
services/dashboard-backend/src/test/js/app.test.cjs
services/dashboard-backend/src/test/js/operations.test.cjs
services/live-feed-producer/src/main/java/com/live_jgv/live_feed_producer/entrypoint/rest/MatchSimulationController.java
services/live-feed-producer/src/main/resources/application.properties
services/live-feed-producer/src/test/java/com/live_jgv/live_feed_producer/entrypoint/rest/SimulatorDisabledTest.java
services/live-feed-producer/src/test/java/com/live_jgv/live_feed_producer/entrypoint/rest/SimulatorEnabledTest.java
```

ScoreProjection y application.properties del dashboard contienen cambios
acumulados de 9A y 9B. Se conservó íntegra su lógica de retry/DLQ/validación.
No hay cambios 9B en servicios de estado/DEV 3 ni en contratos compartidos.

H03 y H04 quedan corregidos en código y pruebas locales, con las limitaciones
anteriores. No hubo commits, push, merges ni cambios de rama. Se espera
autorización antes de 9C.
