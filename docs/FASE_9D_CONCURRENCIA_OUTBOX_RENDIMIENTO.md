# Fase 9D — Concurrencia, outbox y rendimiento

Fecha: 15 de noviembre de 2026. Rama: `fix/auditoria-integral-fase9`.
Alcance: H05, H06, M02 y M09. Implementación autorizada después del
[diagnóstico previo](FASE_9D_PLAN_Y_PROTECCION.md). No se inicia 9E.

## 1. Archivos intervenidos exclusivamente en 9D

Inventario de esta fase, no del diff acumulado contra HEAD. Incluye documentos
del punto previo de autorización; algunos archivos ya tenían cambios de 9A-9C.

```text
docker-compose.yml
docs/FASE_9D_PLAN_Y_PROTECCION.md
docs/MODOS_EJECUCION.md
docs/FASE_9D_CONCURRENCIA_OUTBOX_RENDIMIENTO.md
scripts/rotate-dashboard-stream-epoch.sql
services/dashboard-backend/src/main/java/com/live_jgv/dashboard/ScoreProjection.java
services/dashboard-backend/src/main/resources/db/migration/V2__persistent_stream_epoch.sql
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/BoundedProjectionHistoryTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/ConsistentProjectionReadTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/DurableSnapshotIntegrationTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/LocalMvpConfigurationTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/PersistentStreamEpochTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/StreamEpochMigrationIntegrationTest.java
services/dashboard-frontend/src/services/dashboardStream.test.ts
services/match-state-service/src/main/java/com/live_jgv/matchstate/application/service/ProcessAndPublishMatchEvent.java
services/match-state-service/src/main/java/com/live_jgv/matchstate/domain/model/MatchState.java
services/match-state-service/src/main/java/com/live_jgv/matchstate/domain/port/ScoreUpdateOutbox.java
services/match-state-service/src/main/java/com/live_jgv/matchstate/infrastructure/jdbc/DurableEventIngress.java
services/match-state-service/src/main/java/com/live_jgv/matchstate/infrastructure/jdbc/JdbcMatchStateStore.java
services/match-state-service/src/main/java/com/live_jgv/matchstate/infrastructure/jdbc/PostgresPublicationLock.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/application/service/ProcessAndPublishMatchEventTest.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/domain/model/MatchStateReplayTest.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/jdbc/JdbcHistoryReplayTest.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/jdbc/JdbcMatchStateStoreIntegrationTest.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/jdbc/JdbcPublicationSessionTest.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/jdbc/PostgresPublicationLockTest.java
```

## 2. H05 — Coordinación implementada

`ProcessAndPublishMatchEvent` coordina proceso/recuperación con monitores locales
por partido. Cada drenaje durable entra en `withPublicationLock` antes de leer.
`PostgresPublicationLock` obtiene una conexión y ejecuta pg_try_advisory_lock con
una clave de 64 bits derivada de SHA-256 de namespace estable y match_id.
Una colisión solo serializaría partidos adicionales. No depende del hash de JVM.
Un lock ocupado lanza error reintentable: no se omite el partido ni se declara
completada la recuperación inicial. Los reintentos existentes de Kafka/startup
conservan la custodia durable, incluso si el evento ya fue aplicado.

La conexión propietaria permanece retenida hasta terminar el drenaje y liberar
el lock. Todas las lecturas de payload/destino y el marcado de publicación usan
un JdbcTemplate ligado a esa misma conexión, sin reconexión transparente. No se
abre una segunda conexión del pool para confirmar la outbox. Las consultas de
publicación y de lock tienen timeout de cinco segundos; la confirmación Rabbit
conserva su timeout existente. Se rechazan transacción ambiente y autocommit=false.
**No hay transacción JDBC abierta durante llamadas a RabbitMQ.**

El desbloqueo y la eliminación del contexto ThreadLocal están en finally; la
conexión se cierra con try-with-resources. Si falla unlock, o si se pierde la
respuesta de adquisición y no puede saberse si se obtuvo el lock, se aborta la
conexión física antes de devolverla al pool. Si abort falla se intenta cerrar
la conexión subyacente; los errores de limpieza se conservan como suppressed
cuando ya existe un error principal. Nunca se continúa enviando con otra sesión.

Se leen lotes de hasta 128 entradas, por application_seq, y se repite hasta vacío.
Solo se marca tras el confirm positivo y ausencia de return. Payload, destino,
persistencia Rabbit e identidad lógica se conservan. El inbox del consumidor
mantiene deduplicación y rechazo de contenido distinto para el mismo ID.

Se elimina el monitor global de publicación. La entrada durable usa monitores
por clave únicamente para la custodia local y los libera antes de Rabbit; las
transacciones, claves únicas y CAS siguen protegiendo la persistencia. Replay
conserva sus bloqueos SQL y aprobación por revisión. Los conflictos concurrentes
se reintentan o requieren nueva revisión según las reglas existentes.
Partidos distintos pueden progresar mientras otro espera confirmación; el
listener Kafka sigue configurado con concurrencia 1, sin prometer más throughput
del consumidor que el configurado.

Caída antes de publicar: otro propietario retoma las filas no marcadas cuando
PostgreSQL termina la sesión. Caída tras envío/confirm y antes del marcado:
reenvío de los mismos bytes. Si el marcado llegó a confirmarse pero se perdió su
respuesta, la siguiente lectura verá published=true y no repetirá esa fila.
La semántica es **al menos una vez**, nunca exactamente una vez.

Se requiere conexión directa o pooling de sesiones: no usar PgBouncer en modo
transaction/statement pooling. Un pool agotado o un servidor que aún no detecta
una sesión muerta retrasa recuperación. La pérdida del vínculo PostgreSQL con
Rabbit accesible puede dejar un envío ya en vuelo del propietario anterior;
el lock no implementa fencing en Rabbit. La exclusión se garantiza entre
publicadores cooperantes mientras la sesión propietaria conserva su lock, no
como ausencia absoluta de duplicados bajo toda partición de red.

Referencias: [locks PostgreSQL 17](https://www.postgresql.org/docs/17/explicit-locking.html),
[retransmisión RabbitMQ](https://www.rabbitmq.com/docs/reliability).
El límite de fencing es una conclusión del diseño entre sistemas independientes.

## 3. H06 — Dashboard singleton

Compose declara una réplica y nombre fijo `live-jvg-mvp-dashboard`. Compose no
permite escalar un servicio con container_name a varias réplicas. Se mantienen
el puerto fijo de loopback, servicios, dependencias y volúmenes existentes.
La prueba YAML verifica réplica, nombre, puertos y opt-in del simulador.

El broker STOMP simple difunde **solo en la JVM local**. Dos dashboards en una
cola Rabbit compiten y dejan clientes de otras JVM sin actualizaciones. No se
afirma escalado horizontal ni se agrega un relay. El nombre fijo protege ese
Docker daemon; fuera de Compose, en otros hosts o JVM manuales, la restricción
es operativa. Swarm y configuraciones que omitan la restricción no están soportados.
Referencia: [Compose container_name](https://docs.docker.com/reference/compose-file/services/#container_name).

## 4. M02 — Historial y reconstrucción

`ScoreProjection.view` mantiene una sola sentencia y su snapshot MVCC de 9C.
La subconsulta reciente limita a 30 **antes** de agregar JSON, ordena por seq
y conserva la salida REST de eventos más recientes primero. Otra subconsulta
devuelve únicamente goles activos, incluidos goles anteriores a esa ventana;
excluye anulaciones posteriores que referencien el gol del mismo partido.
No usa timestamps para decidir orden ni interpreta VAR en el frontend.
La aplicación recibe 30 eventos más los goles activos, no todo inbox.

Se conservan campos REST, nulls, ausencia/404, orden de goles activos y consulta
de confirmación por identidad independiente de la ventana. No se elimina ni
reescribe historia. La proyección de marcador/VAR autoritativa no cambia.

La reconstrucción de MatchState usa un Rebuilder privado que invoca las mismas
validaciones de apply con buffers mutables no expuestos y realiza una copia
inmutable final. Evita copiar mapas crecientes por cada evento: trabajo esperado
lineal de replay, con memoria O(n) porque el agregado conserva todas las
identidades históricas. apply normal sigue retornando estados inmutables.
No se normalizan referencias históricas ni cambian reglas VAR.

JDBC recorre el ResultSet con fetchSize=256 dentro de una transacción únicamente
de base de datos; no materializa una lista intermedia y no publica en esa
transacción. Conserva revisión, secuencia contigua, reglas e identidad de cada
registro en una sentencia. Registrar catálogo valida equipos sin reconstruir
historia; la validación completa de integridad al arrancar permanece.

No hay snapshots persistentes ni índices nuevos. Se usan los índices existentes.
La consulta de goles todavía puede recorrer historia y parsear payload TEXT;
la selección reciente no garantiza un escaneo físico de solo 30 filas con varios
partidos. EXPLAIN/ANALYZE real queda pendiente antes de afirmar latencias o decidir
índices. El polling conserva su cadencia y backoff de 9C; cada poll transfiere
resultados acotados, sin cambiar contratos con endpoints delta nuevos.

## 5. M09 — Época persistente

V2 añade solo stream_epoch: clave boolean singleton, UUID no nulo y fila inicial.
CREATE TABLE IF NOT EXISTS e INSERT ON CONFLICT DO NOTHING hacen idempotentes las
operaciones cuando el objeto tiene la estructura esperada; Flyway además registra
la ejecución. No se modifican V1, inbox, matches ni secuencias. PostgreSQL 17
incluye [gen_random_uuid](https://www.postgresql.org/docs/17/functions-uuid.html)
sin requerir una extensión nueva.

snapshot lee época y último evento en la misma sentencia, falla si falta la fila
de época y no inventa snapshots cuando inbox está vacío. stream_id pasa a
`durable-<epoch>-<matchId>`; revision sigue siendo seq, con los mismos campos y
tipos REST/STOMP. No hay UUID generado por arranque ni caché de época que oculte
una rotación. El perfil memory sigue usando su identidad volátil.

En el cliente existente, reconectar con la misma época conserva la revisión
monótona. Recuperar una época nueva acepta revisión 1 y limpia historia/dedup
previas. Las tramas de conexiones antiguas y de otras épocas no reemplazan ese
estado. No se modificó código productivo del frontend, solo sus pruebas.

La rotación es administrativa con psql, CAS de UUID esperado/nuevo y transacción
corta. No existe endpoint público. Una segunda ejecución con la época anterior
falla sin volver a cambiarla. Debe hacerse con dashboard detenido para forzar
recuperación al reconectar; no se soporta rotación en caliente.

## 6. Pruebas ejecutadas

Resultados de los runners finales, sin sumar repeticiones focalizadas:

| Runner | Pruebas | Fallos | Errores | Skipped |
|---|---:|---:|---:|---:|
| Dashboard Java | 118 | 0 | 0 | 0 |
| Productor Java | 57 | 0 | 0 | 0 |
| Estado Java | 92 | 0 | 0 | 0 |
| React/Vitest | 59 | 0 | 0 | 0 |
| Scripts Node | 23 | 0 | 0 | 0 |
| **Total** | **349** | **0** | **0** | **0** |

Unitarias: reglas y equivalencia de replay (20.003 eventos), duplicados, VAR,
inmutabilidad y política de recuperación. Simuladas: sesiones/locks PostgreSQL,
concurrencia con barreras, fallo de adquisición/unlock, excepciones de publicación,
transacción ambiente rechazada, conexión única para lectura/marcado, drenaje de
350 salidas en lotes, cursor JDBC de 5.000 eventos, corrupción de secuencia/ID/
revisión/reglas, montaje REST acotado y rotación/reinicio con JDBC simulado.
La prueba de 9C con barreras sigue verificando una única lectura consistente.
React prueba reconexión, época estable, rotación con revisión inferior, buffer
de épocas mezcladas y callbacks antiguos. Pruebas locales con Spring/WebSocket
memory y stub HTTP loopback no equivalen a infraestructura distribuida real.

```powershell
mvn -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -f services/dashboard-backend/pom.xml '-Dtest=!DurableSnapshotIntegrationTest,!StreamEpochMigrationIntegrationTest' test
mvn -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -f services/live-feed-producer/pom.xml test
mvn -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -f services/match-state-service/pom.xml '-Dtest=!*IntegrationTest' test
npm.cmd test --prefix services/dashboard-frontend
node --test services/dashboard-backend/src/test/js/operations.test.cjs services/dashboard-backend/src/test/js/app.test.cjs services/dashboard-backend/src/test/js/loading.test.cjs
```

Se ejecutaron además selecciones focalizadas de publicación, replay, locks,
lectura consistente y Compose. Logs Java finales en target/phase9d-test.log.
No se instalaron dependencias ni se ejecutaron clean/package/verify.

## 7. Pruebas omitidas

Excluidas dos clases dashboard: DurableSnapshotIntegrationTest y
StreamEpochMigrationIntegrationTest. Excluidas cinco clases estado:
DurableIngressIntegrationTest, DurableMessagingIntegrationTest,
JdbcMatchStateStoreIntegrationTest, QuarantineMessagingIntegrationTest y
QuarantineMigrationIntegrationTest. No se cuentan como ejecutadas ni skipped.

Se añadieron pruebas opt-in, compiladas pero NO ejecutadas: dos publicadores
sobre PostgreSQL real con publicador simulado; SQL de historial de 20.000 eventos
con gol activo/anulación fuera de la ventana; upgrade V1→V2 preservando payloads,
segunda migración con UUID estable y rotación condicional. No se certifica su
resultado sobre el motor. Quedan pendientes psql administrativo real, planes de
consulta, failover/particiones, múltiples procesos y Rabbit real. No hubo E2E
contra servicios, contenedores ni migración aplicada a ninguna base real.

## 8. Regresiones 9A–9C

Sin regresiones detectadas en las suites locales. Se mantienen contratos de
mensajería, confirma/reintenta Rabbit, identidad de operación, escritura opt-in,
timeouts HTTP, recuperación/backoff del frontend y lectura MVCC de 9C.
No se tocan esqueletos DEV 3, credenciales, dominios compartidos o servicios.
La revisión de época cambia únicamente el valor autorizado de stream_id.
La integración real y el empaquetado siguen sin certificar.

## 9. Riesgos residuales

- Locks de sesión requieren pooling compatible, conexiones disponibles y
  detección de sesiones caídas. No son fencing remoto de Rabbit ni exactly-once.
- Claves de partido observadas retienen monitores locales por vida del proceso;
  no se promete memoria constante ante cantidad ilimitada de partidos/claves.
- Historia completa sigue siendo necesaria para idempotencia/referencias del
  agregado; mejora replay pero no elimina coste O(n) por reconstrucción.
- Escaneo SQL y goles activos pueden crecer; memoria REST es O(30 + goles activos),
  no O(30) si todos los eventos fueran goles activos.
- Singleton fuera de Compose y rotación tras restaurar dependen del operador.
  Rotar época no resuelve desalineación entre respaldos de DB, Kafka y Rabbit.
- SQL, migración y script requieren la integración real pendiente antes de desplegar.

## 10. Git y respaldo

Rama conservada: fix/auditoria-integral-fase9, sin staging, commits, stash, push,
merge, reset ni limpieza. Se preservaron las modificaciones previas y sus
pruebas; los cambios de 9D se superponen solo donde se necesita.
Estado acumulado final: 35 archivos tracked modificados y 34 nuevos (69 en total).
El inventario exclusivo de 9D contiene 26 archivos. Frente al respaldo de inicio
de implementación, 43 de los 50 archivos conservan exactamente sus bytes;
los otros siete son archivos de superposición 9D enumerados en el inventario.

Respaldo inicial de 49 archivos documentado en el plan previo. Segundo respaldo
antes de implementar, 50 archivos verificados SHA-256:
`C:\Users\JUANSE\AppData\Local\Temp\LIVE-JVG-9D-authorized-20261115-024958`.
Contiene files/, manifest.json, tracked.patch binario y HEAD.txt. Se volvió a
verificar: cero discrepancias en el respaldo. No se borran respaldos anteriores.
Las carpetas TEMP requieren retención adicional antes de una limpieza del sistema.
No se borraron JAR, no se detuvieron procesos ni se modificó infraestructura activa.

## 11. git diff --check

Se ejecuta git diff --check para los cambios tracked y se comprueban también los
archivos nuevos con git diff --no-index --check contra /dev/null. Resultado final
aprobado, sin diagnósticos de whitespace. En no-index, código 1 sin diagnósticos
indica diferencias contra el archivo vacío, no un fallo de whitespace.
Los logs/artefactos de pruebas quedan ignorados.

## 12. Migración, restauración y rollback propuestos

**No ejecutado en esta etapa.** Antes de desplegar, validar las pruebas opt-in en
una base desechable autorizada, sin activar esos tests contra la instalación MVP.

1. Asegurar respaldo recuperable y acceso administrativo. Detener el dashboard
   durante el despliegue para que todas las sesiones antiguas reconecten. Esto
   es un procedimiento futuro; no se ha detenido ningún proceso en esta fase.
2. Desplegar V2 con el dashboard autorizado: Flyway aplica la migración aditiva
   antes de construir ScoreProjection. En una instalación V1 conserva filas y
   secuencias, crea una época una vez. No editar checksums de V1 ni hacer repair.
3. Verificar una sola fila en dashboard.stream_epoch y snapshots con el UUID
   esperado. Reiniciar normalmente conserva la época; no ejecutar la rotación
   como parte del startup, healthcheck o automatización de reinicios.
4. Para restaurar/reconstruir: detener entradas y dashboard, restaurar/reconstruir
   con procedimiento autorizado y coordinar custodia/colas de Kafka y Rabbit.
   Aplicar V2 si el respaldo aún no la tiene. Consultar la época restaurada:
   `SELECT epoch FROM dashboard.stream_epoch WHERE singleton=TRUE;`.
5. Registrar ese UUID y generar un UUID nuevo una vez. Desde psql 17 independiente
   y conexión administrativa ya configurada (sin contraseñas en argumentos):

   ```powershell
   psql -X -v ON_ERROR_STOP=1 -v expected_epoch=UUID_RESTAURADO -v new_epoch=UUID_NUEVO -f scripts/rotate-dashboard-stream-epoch.sql
   ```

   Sustituir ambos marcadores por UUID reales revisados. Usar variables de
   conexión/servicio o mecanismo de credenciales aprobado. Un UUID inválido,
   faltante o precondición incorrecta produce error y no confirma la rotación.
   El script usa ON_ERROR_STOP y RAISE EXCEPTION para fallo con salida no exitosa;
   no depende de un código numérico en `\quit`, que psql 17 no admite.
6. Verificar el UUID y el historial intacto antes de reanudar. Si se perdió la
   respuesta, consultar la época: si coincide con UUID_NUEVO, la operación ya
   terminó; no generar otro UUID para un reintento ciego. Reiniciar dashboard
   provoca reconexión; comprobar aceptación de revisión inferior en nueva época.

Rollback de aplicación: detener dashboard y volver a la versión anterior,
conservando tabla stream_epoch, fila y registro Flyway V2. La tabla adicional no
interfiere con consultas V1. Reconectar clientes porque la versión anterior usa
stream_id legado; vuelve también el riesgo M09 que tenía esa versión. No borrar
historia, no DROP de tabla ni modificar flyway_schema_history. Una versión vieja
con configuración Flyway que rechace migraciones futuras necesita comprobarse
antes del rollback; no desactivar validación de forma improvisada.

Una rotación confirmada no debe deshacerse devolviendo un UUID viejo que clientes
ya conocen. Si se necesita corregir una restauración, mantener dashboard detenido,
revisar datos/colas y efectuar una nueva rotación administrativa autorizada.
Si V2 falla dentro de su transacción PostgreSQL, verificar rollback y estado
Flyway antes de reintentar; no hacer limpieza destructiva ni repair automático.

La fase termina con código y validación local; el despliegue y la integración real
siguen pendientes. **No iniciar 9E sin autorización.**
