# FASE 7H — Persistencia y recuperación durable

Fecha: 2026-11-14, America/Bogota. Rama conservada:
`feature/Valeria-infra-history`. Resultado: **PASS del bloque autorizado**.

## Alcance y preparación

Se revisaron íntegramente el [diseño 7G](phase-7g-reliability-design.md), la
[auditoría 7F](phase-7f-robustness-report.md), código y pruebas reales antes de
editar fuentes. Git contenía docs/ sin seguimiento desde fases anteriores; se
conservó. No se cambiaron rama, productor, servicios ajenos, contratos o Compose.

Del plan de diez cambios, este bloque implementa G2–G5: revisión explícita,
JDBC/migración, ledger/outbox durable y drenaje al arrancar. Se ejecutó la parte
de integración aislada de G10 necesaria para verificarlo. G1 sigue pendiente en
cuanto a aprobación de schemas; mantener su forma actual no depende de añadir
campos ni aprobar v2. G6–G9 (cuarentena, replay, política nueva de retries,
inbox downstream/v2) NO se implementaron. No se encontraron decisiones que
impidieran el bloque de persistencia expresamente autorizado por el usuario.

## Arquitectura implementada

- `LoadedMatch` transporta estado y revisión explícita. `MatchStateRepository`
  carga ese snapshot y hace CAS por revisión, sin depender de identidad Java.
  El store de memoria se adapta al mismo puerto, pero solo se registra mediante
  perfil `memory` explícito.
- `JdbcMatchStateStore` recupera catálogo e historial con una sola consulta SQL,
  de modo que comparten snapshot MVCC. Reaplica eventos en application_seq para
  reconstruir el agregado existente; esa carga no publica ni crea otra outbox.
- La revisión debe coincidir con la longitud del historial contiguo. Historia
  corrupta, versión de reglas desconocida o catálogo incompatible producen error,
  no un marcador nuevo a cero.
- `TransactionTemplate` envuelve CAS, inserción del evento y salida pendiente.
  Una excepción SQL revierte las tres operaciones. Las restricciones únicas
  protegen identidad `(match_id,event_id)` y secuencia por partido.
- La deduplicación compara los records de evento completos recuperados del ledger:
  no hace falta una tabla/hash adicional para APPLIED en este bloque sin DEFERRED.
  Un mismo ID con contenido distinto se sigue rechazando. No se usan bytes del
  JSON de entrada ni igualdad por hash como identidad de negocio.
- La outbox guarda payload exacto, destino y routing key. `StoredScoreUpdate` y
  `publishStored` permiten enviar los bytes guardados, sin reserializarlos ni
  recalcular updated_at. Se mantienen message_id, UTF-8, persistencia AMQP,
  mandatory y confirms correlacionados.
- `ProcessAndPublishMatchEvent` publica fuera de la transacción SQL y después
  registra la confirmación. Si falla cualquiera de estas operaciones, el listener
  propaga error y Kafka no confirma el evento como completado.
- `DurableRecoveryRunner` mantiene Kafka detenido y readiness REFUSING_TRAFFIC
  mientras drena todas las salidas pendientes. Reintenta cada 5 s por defecto.
  Solo al completar arranca el registry, respetando auto-startup=false cuando
  esté configurado. No necesita un nuevo evento del partido para recuperar la salida.
- No hay fallback automático a memoria si PostgreSQL o Flyway fallan. El arranque
  inicial requiere BD disponible y migración válida; la caída de BD durante
  consumo se recupera mediante los reintentos existentes de Kafka.

El estado durable del marcador es el catálogo + ledger ordenado, no una columna
mutable de home_score/away_score. También se conservan las referencias de goles,
anulaciones, historial de dedup y estado VAR. No se cambió el agregado ni sus reglas.

## Migración y dependencias

Se mantienen Java 17 y Spring Boot 3.5.7. Dependencias añadidas al POM del servicio:

| Dependencia | Versión resuelta por Boot | Finalidad |
|---|---|---|
| spring-boot-starter-jdbc | 3.5.7 | JdbcTemplate, transacciones y pool Hikari |
| postgresql | 42.7.8, runtime | Driver PostgreSQL |
| flyway-core | 11.7.2 | Migración versionada |
| flyway-database-postgresql | 11.7.2 | Soporte PostgreSQL de Flyway |

Flyway crea el esquema propio `match_state` y aplica
`V1__durable_match_state.sql`. Tablas: `match_catalog`, `accepted_event` y
`score_outbox`; índice parcial para pendientes. No se crearon tablas de cuarentena,
DLT ni contratos nuevos. `spring.flyway.clean-disabled=true` impide clean.

El JSON de evento y outbox se guarda en TEXT para conservar la precisión de
Instant, incluyendo nanosegundos. No se usa timestamp SQL de microsegundos para
reconstruir campos cuya igualdad determina deduplicación.

## Entorno de pruebas

Pruebas ejecutadas directamente por Codex con Java 17.0.20.1 y Maven 3.10.0.
Se conservaron los brokers existentes y se crearon únicamente recursos nuevos:

- PostgreSQL `postgres:17`, contenedor `live-jvg-7h-postgres`, puerto local 55432.
- Volumen nuevo `live-jvg-7h-pgdata`; no se eliminó y conserva los datos.
- Bases nuevas `phase7h_tests` y `phase7h_brokers`, separadas para fixtures SQL
  y pruebas completas de mensajería.
- Kafka `live-jvg-test-kafka-1`, bootstrap localhost:9092. Cada ejecución usa
  topic nuevo de una partición y grupo independiente.
- RabbitMQ `live-jvg-test-rabbitmq-1`, AMQP localhost:5672. Cada ejecución usa
  exchange topic y cola durables nuevos, routing score.{matchId}.

Las credenciales PostgreSQL del contenedor son desechables de prueba; no son
credenciales reales ni se agregaron como secretos/configuración del repositorio.
Las pruebas usan variables de entorno. No se tocó ninguna BD anterior.

## Resultados Maven

Comandos base realmente ejecutados:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  /opt/homebrew/bin/mvn -o -f services/live-feed-producer/pom.xml clean verify

# Con las variables de pruebas indicadas debajo habilitadas:
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  /opt/homebrew/bin/mvn -o -f services/match-state-service/pom.xml clean verify
```

La primera resolución de las dependencias nuevas se ejecutó online; las
verificaciones posteriores fueron offline. Todas las versiones nuevas proceden
del dependency management existente de Boot.

| Servicio / verificación final | Pruebas | Fallos | Errores | Omitidas | Resultado |
|---|---:|---:|---:|---:|---|
| live-feed-producer, sin cambios de código | 43 | 0 | 0 | 0 | BUILD SUCCESS, código 0 |
| match-state-service | 86 | 0 | 0 | 0 | BUILD SUCCESS, código 0 |

Total final: **129 pruebas**. Las 86 de match-state incluyen 73 previas, ocho de
PostgreSQL, tres de gate de arranque, una del envío del wire image y una prueba
completa con brokers que contiene múltiples aserciones de recuperación.

Las pruebas PostgreSQL y de brokers son opt-in: sin variables de habilitación
no se deben presentar como ejecutadas. La ejecución final sí habilitó ambas,
como confirman los XML Surefire archivados (0 omitidas).

## Matriz de pruebas obligatorias

| Escenario | Nivel y evidencia | Resultado |
|---|---|---|
| GOAL local y visitante | Kafka/PG/Rabbit reales; marcador 1–0 y luego 1–1 | PASS |
| VAR_CHECK | Referencia al gol visitante, marcador 1–1 e IN_PROGRESS | PASS |
| GOAL_ANNULLED | Vuelve a 1–0, CLEAR, referencia conservada | PASS |
| MATCH_CONTINUES | Mantiene 1–0, CLEAR | PASS |
| Reinicio después de GOAL | Cerrar/recrear contexto Spring, mismo grupo y BD: 1–0 recuperado | PASS |
| Reinicio después de anulación | Cerrar/recrear contexto: 1–0 y gol visitante anulado recuperados | PASS |
| Duplicado antes de reiniciar | Segundo GOAL idéntico avanza offset, cola sigue con una salida | PASS |
| Duplicado después de reiniciar | GOAL y anulación idénticos no aumentan historial ni salidas | PASS |
| PostgreSQL rechaza outbox a mitad de persistencia | Trigger de fallo exclusivo del fixture; revisión, ledger y outbox vuelven a cero | PASS |
| Reintento tras retirar ese fallo SQL | Una aceptación y una outbox, sin efecto parcial previo | PASS |
| PostgreSQL no disponible durante consumo | Detener solo el contenedor nuevo 7H: offset permanece en 8 y no aparece otra salida | PASS |
| Restaurar PostgreSQL | Arrancar mismo contenedor/volumen: offset 9, marcador 2–0, una salida nueva | PASS |
| Pendiente durable al arrancar sin nuevas entradas | Aceptar en BD antes de enviar, recrear contexto: se drena pendiente, offset sigue 9 | PASS |
| No republicar historial | Cantidad de salidas permanece estable en reinicios sin pendientes | PASS |
| Payload/updated_at/destino estables | Igualdad byte a byte para salida pendiente y destino original tras cambio de configuración del store | PASS |
| Coherencia de historial y marcador | Revisión 7, siete aceptados, siete outbox publicadas, marcador final 2–1 | PASS |
| CAS concurrente | Dos escritores, misma revisión: solo uno confirma ledger/outbox | PASS |
| Identidad/catálogo inválidos | Partido desconocido y equipos incompatibles rechazados; sin creación desde GOAL | PASS |
| Precisión temporal | Timestamp/updated_at con nueve decimales sobreviven a carga y dedup | PASS |
| Historia inconsistente | Revisión manipulada en fixture: carga falla sin resetear estado | PASS |
| Arranque y retries | No iniciar Kafka hasta drenaje exitoso; cierre/disable flag respetados | PASS |
| JSON/contratos actuales | Suites previas + siete payloads reales con exactamente los ocho campos actuales | PASS para compatibilidad observada |

Las pruebas de reinicio recrean contextos completos Spring con pools, registry y
stores nuevos, manteniendo offsets y PostgreSQL. No matan abruptamente la JVM;
el caso pendiente simula la frontera commit-antes-de-envío mediante aceptación
real sin publicación. No se afirma ensayo de todas las ventanas de crash.

## Evidencia de la ejecución final

Prefijo final: `phase7h-3d5d9b88-2592-439e-8b75-9b04639ce108`.
Sufijos: -events, -group, -queue, -updates y -match. Todos son ajenos a 7E.

| Orden aplicado / ID como sufijo del prefijo | Marcador publicado | VAR |
|---|---|---|
| home | 1–0 | CLEAR |
| away | 1–1 | CLEAR |
| review | 1–1 | IN_PROGRESS |
| annul | 1–0 | CLEAR |
| continue | 1–0 | CLEAR |
| during-outage, aplicado tras restaurar PG | 2–0 | CLEAR |
| pending-at-restart, drenado sin nuevo registro Kafka | 2–1 | CLEAR |

Los duplicados Kafka home y annul no aparecen otra vez en esta tabla: nueve
registros Kafka producen seis aplicaciones nuevas. La séptima aceptación es
el fixture real de pendiente guardado directamente antes del reinicio. Así,
el grupo termina con offset 9 y el historial durable con revisión 7; no se
confunden ambos contadores.

Extractos reales de la verificación:

```text
7H PASS restart after GOAL retains 1-0 without republishing history
7H PASS restart after annulment; duplicate goal and annulment do not publish twice
7H PASS PostgreSQL down: Kafka offset frozen at 8, no output
7H PASS PostgreSQL restored: offset 9
7H PASS startup drains pending without new Kafka record; revision 7, score 2-1, queue 7
```

La inspección de los siete mensajes de la cola se hizo mediante basicGet con
autoAck=false y basicNack con requeue=true, conservándolos. Se verificó identidad
única, match_id, forma JSON y bytes de la salida pendiente. El transporte de
entrada del test usa KafkaTemplate real con JSON del modelo actual; no vuelve a
ejecutar el bean del productor desde JShell. La regresión del productor verifica
su adaptador existente y 7E conserva esa validación histórica de extremo a extremo.

Evidencias en [evidence/phase-7h](evidence/phase-7h/): logs finales completos,
resumen de mensajes/checks, XML Surefire de ambos módulos, inspección final SQL,
colas, grupo 7E y recurso/volumen PostgreSQL. Los hashes SHA-256 permiten comprobar
integridad. No se sustituyeron ni editaron evidencias 7E/7F/7G.

## Fallos encontrados y correcciones

La primera regresión con ocho tests SQL pasó (81 pruebas). La primera prueba
completa con brokers también pasó. Al añadir los tres tests del gate de arranque,
una expectativa Mockito verificaba dos llamadas idénticas como dos verificaciones
individuales de una llamada y falló por contarlas juntas. Se corrigió a times(2)
antes de verificar registry.start. Fue un fallo de la prueba nueva, no pérdida
de datos ni un error de la prueba real, que había pasado en la misma ejecución.
Se conservó ese log y se repitió la suite completa: 86/0/0/0.

En la revisión se cambió el gate de listeners para consultar el perfil activo,
no una propiedad storage que podría permitir un arranque prematuro si no coincidía
con el perfil. Se probó el gate y la recuperación real después de ese cambio.

## Configuración para uso durable y reproducción

Modo predeterminado durable, sin perfil memory. Variables requeridas para una
BD PostgreSQL propia de match-state-service:

```text
MATCH_STATE_DB_URL=jdbc:postgresql://<host>:<puerto>/<base>
MATCH_STATE_DB_USERNAME=<usuario del servicio>
MATCH_STATE_DB_PASSWORD=<secreto externo>
```

Valores de desarrollo por defecto: URL localhost:5432/match_state, usuario
match_state y contraseña vacía; no son una provisión de infraestructura ni una
credencial de producción. Flyway necesita permisos de creación/migración en el
esquema match_state. La inicialización durable no cambia el Compose del repositorio.

El catálogo inicial continúa configurándose con MATCH_ID, HOME_TEAM_ID,
AWAY_TEAM_ID o match-state.matches. Solo se insertan partidos nuevos: nunca se
sobrescriben equipos ni se resetea historial existente. KAFKA_BOOTSTRAP_SERVERS y
variables RabbitMQ existentes mantienen su significado.

Perfil `memory` explícito: únicamente pruebas y ejecuciones desechables, con la
advertencia de volatilidad; desactiva autoconfiguración de BD y Flyway. La selección
real del modo la controla el perfil, no cambiar match-state.storage aisladamente.
`KAFKA_LISTENER_AUTO_STARTUP=false` no omite el drenaje durable, solo impide iniciar
el listener al terminarlo. `match-state.recovery.retry-interval` controla la espera
de arranque; no sustituye la política Kafka de errores vigente.

Para repetir los tests reales en un entorno aislado, el código de prueba consume:

```text
MATCH_STATE_TEST_DB_URL=jdbc:postgresql://localhost:55432/phase7h_tests
MATCH_STATE_BROKER_DB_URL=jdbc:postgresql://localhost:55432/phase7h_brokers
MATCH_STATE_TEST_DB_USERNAME=match_state
MATCH_STATE_TEST_DB_PASSWORD=<contraseña desechable del contenedor 7H>
MATCH_STATE_TEST_BROKERS=true
MATCH_STATE_TEST_POSTGRES_CONTAINER=live-jvg-7h-postgres
```

El test completo detiene/restaura ese contenedor indicado; requiere Docker y
solo acepta nombres con prefijo live-jvg-7h-. No apuntar estas variables a
infraestructura compartida. Sus recursos de mensajes usan UUID nuevos. Las dos
bases fueron creadas nuevas por esta fase. Los fixtures SQL eliminan únicamente
su propio trigger temporal de inyección, sin borrar datos del servicio.

## Archivos modificados y añadidos

Todos los cambios de código/configuración/pruebas están bajo
`services/match-state-service/`:

| Grupo | Archivos |
|---|---|
| Dependencias | pom.xml |
| Modelo/puertos | domain/model/LoadedMatch.java, StoredScoreUpdate.java; domain/port/MatchStateRepository.java, ScoreUpdateOutbox.java, ScoreUpdatePublisher.java |
| Aplicación | application/service/MatchStateService.java, ProcessAndPublishMatchEvent.java |
| Persistencia | infrastructure/jdbc/JdbcMatchStateStore.java |
| Configuración/arranque | infrastructure/config/MessagingConfiguration.java, DurableStorageConfiguration.java, DurableRecoveryRunner.java |
| Adaptadores existentes | infrastructure/memory/InMemoryMatchStateStore.java; infrastructure/rabbit/RabbitScoreUpdatePublisher.java |
| Recursos | application.properties, application-memory.properties, db/migration/V1__durable_match_state.sql |
| Tests adaptados | MatchStateServiceApplicationTests.java; application/service/MatchStateServiceTest.java; infrastructure/rabbit/RabbitScoreUpdatePublisherTest.java |
| Tests nuevos | infrastructure/jdbc/JdbcMatchStateStoreIntegrationTest.java, DurableMessagingIntegrationTest.java; infrastructure/config/DurableRecoveryRunnerTest.java |

Las rutas de modelo/puertos/aplicación/infraestructura de esta tabla son relativas
a src/main/java/com/live_jgv/matchstate; las de tests a su equivalente src/test/java
y recursos a src/main/resources. Documentación nueva: este informe y su carpeta
de evidencias. Los target se regeneraron, sin seguimiento Git.

## Límites y riesgos pendientes

- Una instancia de servicio/publicación por almacén. CAS protege escrituras,
  pero no garantiza orden de publicación multiinstancia. No se habilitó escalado.
- La incertidumbre entre Rabbit confirm y markPublished todavía puede causar
  reentregas. La outbox asegura conservación, no exactly-once ni inbox downstream.
- El arranque espera globalmente a que RabbitMQ permita drenar todos los
  pendientes. Puede bloquear readiness de todo el servicio; es el gate simple
  previsto en 7G. No se añadió dispatcher asíncrono ni readiness HTTP.
- La historia crece y rehidratar puede tener coste elevado; sin snapshots,
  purga o retención automática. No se ha medido rendimiento a gran escala.
- No hay importación automática de antiguos marcadores en memoria. Antes de
  migrar un despliegue con offsets confirmados se requiere corte y reconstrucción
  controlados; una BD vacía no recupera por sí sola historia Kafka ya consumida.
- Restaurar una copia de BD atrasada respecto a offsets requiere reconciliación;
  backup/restore operativo y crash abrupto de JVM/host no fueron ensayados aquí.
- El volumen de prueba se conserva; producción necesita política de backup,
  credenciales, roles y procedimientos aprobados por DEV 3.
- Mensajes inválidos/anulaciones adelantadas siguen la política de reintento
  indefinido actual. Cuarentena/replay y clasificación nueva son el siguiente bloque.
- Contratos compartidos siguen pendientes de aprobación, sin ningún cambio
  de JSON, topic público, clave o routing de producción.

No se corrigieron problemas fuera de alcance. El PASS se refiere a persistencia,
deduplicación y recuperación verificadas, no al cierre completo del proyecto.

## Conservación y cierre

Se conserva la cola 7E con cinco mensajes y su grupo con offset 5, lag 0, según
inspección final. Los hashes de las 33 evidencias 7F siguen intactos. Los
contenedores Kafka/RabbitMQ y procesos existentes no se reiniciaron. El único
contenedor detenido/restaurado fue el PostgreSQL nuevo de 7H, que queda activo
con su volumen. No se eliminaron BD, recursos o evidencias existentes.

Siguiente bloque recomendado: G6–G8, cuarentena durable y tratamiento de
referencias diferidas/replay, conservando la transacción de aceptación actual;
requiere autorización específica. Después, coordinación con DEV 2/3 para inbox
y revisiones de snapshots.

Sin commits, merges, pushes ni cambio de rama. Trabajo terminado para revisión.
