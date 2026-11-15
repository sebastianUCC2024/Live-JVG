# FASE 7F — Auditoría y pruebas de robustez

Fecha local: 2026-11-14, America/Bogota. Ejecución directa de Codex.

**Resultado global: PARTIAL.** Las suites existentes y las garantías locales de
deduplicación/reintento pasan. La recuperación durable del marcador falla tras
reiniciar el consumidor de prueba con offsets confirmados. Mensajes inválidos y
anulaciones adelantadas bloquean la partición, conforme a la política implementada.
El PASS funcional histórico de 7E se conserva, sin repetir su secuencia.

## Alcance y aislamiento

Se revisaron código, POM, configuraciones, propuestas y pruebas de
live-feed-producer y match-state-service. Se inspeccionó la estructura de docs
antes de crear este informe separado del histórico 7E. No se encontraron
AGENTS.md dentro del repositorio en la búsqueda realizada.

Se utilizaron Java 17.0.20.1, Maven 3.10.0 ya instalado, Kafka apache/kafka:3.9.1
y RabbitMQ rabbitmq:4-management existentes. Los brokers NO fueron detenidos,
reiniciados ni reconfigurados.

El ejecutor temporal `/private/tmp/live-jvg-7f/Robustness.java` utiliza clases
reales compiladas del repositorio, KafkaTemplate, KafkaMatchEventPublisher,
contextos Spring de MatchStateServiceApplication y RabbitScoreUpdatePublisher.
No usa Mockito en las pruebas nuevas con brokers. No añade implementación ni
dependencia a los microservicios. Su fuente queda archivada como evidencia textual
en [robustness-harness-source.txt](evidence/phase-7f/robustness-harness-source.txt).

Cada ejecución usó UUID nuevos, topics propios de una partición, grupos, exchange,
colas y partidos nuevos. No se publicó en match_events ni en live_updates. La
excepción es la inspección de solo lectura del estado del grupo/cola 7E.

La prueba de reinicio cerró y recreó exclusivamente un contexto Spring creado
por el harness. Esa intervención era necesaria para comprobar recuperación con
el mismo grupo. No reinició el proceso del usuario ni su consumidor 7E.

## Matriz de resultados

PASS significa comportamiento comprobado dentro del alcance indicado; FAIL
señala incumplimiento de la garantía evaluada, aunque el código documente ese límite.

| Caso | Nivel | Resultado | Evidencia observada |
|---|---|---|---|
| Productor clean verify | Suite existente | PASS | 43 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Match-state clean verify | Suite existente | PASS | 73 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Mapeo JSON, enums, campos obligatorios, claves y coerciones | Pruebas existentes | PASS | Tests de contrato estructural, modelo, listener y publisher |
| Duplicado exacto de GOAL | Kafka + consumidor + RabbitMQ reales | PASS | Offset avanza, sin segundo incremento ni salida adicional |
| Duplicado exacto de GOAL_ANNULLED | Kafka + consumidor + RabbitMQ reales | PASS | Sin segunda resta; dos salidas para cuatro registros |
| Mensaje RabbitMQ no enrutable | Brokers reales | PASS | Pendiente retenido, marcador aplicado una vez y offset sin avanzar |
| Recuperación al añadir el binding de prueba | Brokers reales | PASS | Se vacía pendiente y se confirma offset 1, sin aplicar el gol de nuevo |
| Reinicio con offsets confirmados | Spring + Kafka reales | FAIL para recuperación durable | El marcador previo 1–0 vuelve a 0–0 |
| Gol posterior al reinicio | Brokers reales | FAIL para continuidad del marcador | Se publica 0–1 en vez de 1–1 conservando el historial completo |
| JSON inválido seguido de gol válido | Kafka + listener reales | PASS para rechazo; FAIL para progreso automático | Reintentos, sin aplicar eventos ni avanzar más allá del registro inválido |
| Anulación antes del gol referenciado | Kafka + dominio reales | PASS para rechazo; FAIL para recuperación automática del orden | El gol posterior no puede alcanzarse en la misma partición |
| Conexión RabbitMQ rechazada | Componente real, endpoint sin listener | PASS | Excepción y pendiente retenido |
| Reintento con conexión RabbitMQ operativa | Componente + broker reales | PASS | Un gol, una salida y pendiente vacío |
| Corte y restauración de conexión AMQP del consumidor | Cadena Kafka → consumidor → RabbitMQ real, proxy propio | PASS | Offset congelado en 1 durante el corte; después offset 2, dos salidas y marcador 1–1 |
| Kafka inaccesible para el publicador | Adaptador real, endpoint sin listener | PASS | Futuro excepcional; no confirmación ficticia |
| Nack, timeout, interrupción, retornos y fallos de envío | Suites existentes con transporte simulado | PASS | RabbitScoreUpdatePublisherTest, KafkaMatchEventPublisherTest |
| Conflicto de ID con contenido distinto, equipos ajenos y referencias inválidas | Suites existentes | PASS | Rechazo sin mutación, sin escritura indebida |
| Timestamps antiguos / orden de llegada | Suite existente | PASS para comportamiento actual | No reordena; la semántica temporal requiere aceptación del equipo |
| Pérdida de estado/outbox al recrear store | Suite existente | PASS como demostración del límite | No existe recuperación persistente |
| Preservación de 7E | Lectura de infraestructura | PASS | Cola 5/0 ready/unacked; grupo offset 5, log-end 5, lag 0 |
| Validación completa JSON Schema Draft 2020-12 | Sin validador disponible | BLOCKED | Schemas compartidos vacíos; pruebas actuales son estructurales |
| Caída/reinicio real de brokers, particiones de red, failover | No ejecutado | NOT TESTED | Brokers utilizados por evidencia 7E; no se intervinieron |
| Caída entre Rabbit ack y eliminación del pendiente / commit Kafka | No ejecutado | NOT TESTED | Requiere inyección controlada de fallos en entorno separado |
| Múltiples instancias y rebalance con transferencia de estado | No ejecutado | NOT TESTED | Adaptador volátil sin transferencia de estado |
| Validación de consumidores DEV 2/DEV 3 | Solo inspección de estructura | NOT TESTED | Implementaciones presentes son clases de arranque; no se probó integración ajena |

El harness principal terminó con `ROBUSTNESS_COMPLETED` y 24 aserciones comprobadas.
El ejecutor adicional terminó con `NETWORK_RECOVERY_COMPLETED` y nueve aserciones
comprobadas: 33 en conjunto, distintas de las 116 pruebas Maven. Dos
aserciones comprueban explícitamente la pérdida de estado y el resultado incorrecto
tras reinicio: sus mensajes internos `CHECK PASS OBSERVED ...` significan que el
defecto fue reproducido, NO que la persistencia funciona. La matriz los clasifica
como FAIL para la garantía de recuperación.

## Maven: resultados reales y diagnóstico de ejecución

Primer intento: Maven 3.9.16 offline no reconoció los artefactos cacheados bajo
el identificador de repositorio de Maven 3.10.0. No fue fallo de tests ni ausencia
física del parent. Los reintentos online con 3.9.16 se interrumpieron al identificar
la distribución local correcta; no se computan como builds terminados.

Se utilizó `/opt/homebrew/bin/mvn` 3.10.0 ya instalado, con Java 17 y acceso local
ampliado. No se modificaron settings, POM, dependencias, wrappers ni versiones.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  /opt/homebrew/bin/mvn -o -f services/live-feed-producer/pom.xml clean verify
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  /opt/homebrew/bin/mvn -o -f services/match-state-service/pom.xml clean verify
```

Ambos: BUILD SUCCESS y código 0. Los totales 43/73 se verificaron también sumando
los reportes Surefire XML archivados en [surefire](evidence/phase-7f/surefire/).
Son 116 pruebas; no se presenta una cifra de cobertura porcentual, pues no se
ejecutó instrumentación de cobertura. No hay Failsafe/Testcontainers/EmbeddedKafka
configurados en los POM inspeccionados; la prueba MessagingPipelineTest existente
es de componentes con RabbitTemplate simulado.

## Pruebas reales: recursos y evidencias

Prefijo de la ejecución completa: `phase7f-62d7304b-3842-4914-b9bb-0132fe594a81`.

| Sufijo del recurso | Uso |
|---|---|
| -main | Topic de duplicados, retornos, recuperación de binding y reinicio |
| -invalid | Topic de JSON inválido seguido de un gol |
| -early | Topic de anulación adelantada seguida de su gol |
| -group / -invalid-group / -early-group | Grupos independientes |
| -updates | Exchange topic durable propio |
| -queue | Cola durable con cuatro salidas acumuladas |
| -connection-queue | Cola durable con una salida tras recuperación de conexión |
| -match / -connection | Partidos propios, equipos home/away |

El publicador real se construye con KafkaTemplate y mapper reales. Los contextos
del consumidor usan su application.properties real, topic/grupo/exchange de prueba
y catálogo explícito. Se espera asignación de partición y se comprueban offsets
con AdminClient; no se resetea ningún grupo.

En -main, los offsets confirmados avanzaron 1, 2, 3, 4, 5 y 6. Antes del reinicio:
GOAL, duplicado, anulación, duplicado, nuevo GOAL local. Solo los tres eventos
nuevos produjeron salidas. Después de reiniciar el contexto con el mismo grupo,
el GOAL visitante produjo la cuarta salida, con marcador 0–1.

Payloads reales conservados en
[main-queue-payloads.txt](evidence/phase-7f/main-queue-payloads.txt):

| event_id | Tipo | Marcador publicado |
|---|---|---|
| 50963576-d2a4-4056-b09e-479f99abde44 | GOAL | 1–0 |
| f527b29e-19f3-444e-b5c8-dc9090c2af81 | GOAL_ANNULLED | 0–0 |
| 13a21601-a75e-4350-8c4f-5305ebf07515 | GOAL | 1–0 |
| 83753b53-20bc-466d-a686-47afe880ebdd | GOAL | 0–1, tras reinicio |

El caso de conexión RabbitMQ rechazada usa un puerto local sin listener, afecta
solo al cliente de prueba y ejecuta ProcessAndPublishMatchEvent con store real.
Después se reintenta con publisher conectado al broker real y el mismo store.
No es una caída del broker ni una prueba Kafka end-to-end de ese fallo de conexión.
El caso no enrutable sí verifica el offset Kafka durante el fallo y recuperación.

La prueba Kafka inaccesible usa otro puerto local sin listener y max.block.ms=1000,
request.timeout.ms=1000, delivery.timeout.ms=3000 solo en ese producer de prueba.
Verifica un futuro excepcional ante imposibilidad de obtener metadata, no una
caída/reconexión de un broker previamente conectado. No se afirma recuperación
Kafka ni partición de red como resultado observado.

### Interrupción AMQP controlada y recuperación de extremo a extremo

Se ejecutó además `/private/tmp/live-jvg-7f/RabbitRecovery.java`, fixture temporal
con un proxy TCP propio entre un consumidor nuevo y RabbitMQ. El proxy cerró
exclusivamente sus sockets y rechazó reconexiones durante la interrupción. No
alteró puertos del broker, redes Docker ni conexiones de otros procesos.

Prefijo: `phase7f-ce8c1403-7c76-435c-82f8-c889dc65b2f2`. Topic -network, grupo
-network-group, cola -network-queue y exchange -updates. El consumidor se configura
con el puerto efímero del proxy mediante una propiedad solo del harness.

1. GOAL local: offset confirmado 1 y una salida 1–0.
2. Corte del enlace AMQP y publicación de GOAL visitante: pendiente conservado,
   offset todavía 1 y cola todavía con un único mensaje tras 1,8 s de observación.
3. Restauración del mismo proxy, sin reiniciar el consumidor: offset 2,
   pendiente vacío, dos mensajes y marcador 1–1. No hubo doble efecto observado.

Son nueve aserciones reales, con logs y payloads archivados en
[rabbit-network-recovery.txt](evidence/phase-7f/rabbit-network-recovery.txt) y
[network-queue-payloads.txt](evidence/phase-7f/network-queue-payloads.txt).
Esto sí verifica reintento y commit Kafka durante pérdida/restauración del enlace
AMQP. No prueba un reinicio del broker ni un crash del consumidor en una ventana
de confirmación incierta.

## Incidencias del ejecutor, investigadas antes de concluir

1. La primera declaración de cola no durable/no exclusiva fue rechazada por el
   broker: `transient_nonexcl_queues` deshabilitado/deprecado. Se cambió solo el
   fixture temporal a colas durables nuevas; no se alteró el broker. Quedaron
   topics/exchange del prefijo `phase7f-f5820d90-8b2d-4b42-9841-c8807a7fd8a7`.
2. Un primer classpath combinado cargó application.properties del productor antes
   que el del consumidor. Faltaban confirms RabbitMQ y la prueba expiró esperando
   el offset. Se corrigió únicamente el orden del classpath temporal, poniendo
   match-state-service primero. No era un defecto del código del servicio.
   La ejecución `phase7f-d0d18339-f443-41c4-be6d-24fca76a3c4c` dejó 17 mensajes
   de prueba en su propia cola debido a entrega con confirmación incierta; no se
   cuentan como salidas de la ejecución final ni como duplicados de 7E.
3. El harness inicial tuvo un conflicto de imports Java Queue; se corrigió en
   /private/tmp antes de ejecutar. No se modificó ningún archivo fuente del repo.

Las incidencias conservan sus logs para distinguir errores del fixture de
defectos del producto. Todos los contextos propios se cerraron y la ejecución
final terminó con código 0; no quedaron procesos del harness. Una consulta jcmd
posterior no pudo adjuntarse porque el proceso ya había terminado.

## Contratos, configuración y funcionalidades faltantes

Respaldado por código y suites: JSON snake_case; topic configurable; clave matchId;
los cuatro enums; timestamp Instant; referencia obligatoria para anulación;
consumer con validación estricta, ack por registro y commit después del publisher.
Exchange topic durable, mandatory, mensajes persistentes, confirms correlacionados,
outbox en memoria y reintentos sin límite. La coordinación local es synchronized,
no una transacción distribuida ni bloqueo entre instancias.

Restricciones adicionales del consumidor: equipos registrados, pertenencia de
team_id al partido y existencia/actividad de la referencia. El productor admite
fuera de GOAL un team_id vacío/ajeno que el consumidor rechaza. No se modificaron
schemas para ocultar esta diferencia. Los cuatro archivos compartidos tienen
0 bytes. Las propuestas locales no son contratos aprobados y sus tests no son un
motor completo JSON Schema. No se instaló un validador adicional.

No implementado en este checkout: almacenamiento durable y recuperación del
estado/outbox; cuarentena/DLT o gestión de anulaciones adelantadas; simulador
automático y endpoints REST del productor. La ausencia de REST no invalida la
publicación mediante el adaptador. Las fuentes principales de los otros cinco
servicios inspeccionadas contienen clases de arranque y properties; no se asumió
que exista integración ajena por el mero hecho de compilar esos esqueletos.

## Hallazgos y propuestas concretas

| ID | Severidad | Hallazgo | Propuesta para aprobación |
|---|---|---|---|
| F7F-01 | Alta para operación con reinicios | Pérdida observada de marcador, historial y pendientes; offsets confirmados impiden reconstrucción automática | Diseñar repositorio durable con estado, deduplicación y outbox atómicos; definir recuperación antes de implementar |
| F7F-02 | Alta para disponibilidad | Un inválido o anulación adelantada bloquea la partición indefinidamente | Acordar clasificación de errores, cuarentena durable y replay ordenado; evitar saltos silenciosos de offsets |
| F7F-03 | Alta para integración general | Contratos compartidos vacíos, aprobación pendiente | Revisar propuestas con DEV 2/3, adoptar schemas y validador completo tras autorización |
| F7F-04 | Media | Confirmación incierta permite reenvío AMQP; no existe exactly-once entre brokers | Exigir deduplicación downstream por (match_id,event_id); probar crash windows en entorno separado |
| F7F-05 | Media | Orden por llegada permite eventos antiguos que cambien VAR; memoria/historial crecen sin retención | Acordar semántica temporal y política de retención con recuperación |
| F7F-06 | Media | La robustez real no forma parte de Maven/CI | Tras aprobación, promover el protocolo aislado a integración automatizada y reproducible, sin usar infraestructura compartida |

F7F-01 y F7F-02 son límites conocidos del diseño actual, ahora reproducidos con
brokers; no se presentan como regresiones nuevas. Ninguna propuesta fue implementada.

## Comandos y reproducción

Desde la raíz del repo, después de los builds anteriores, se extrajeron BOOT-INF/lib
de ambos JAR a `/private/tmp/live-jvg-7f/lib` con zipfile de Python. La fuente del
harness archivada permite repetir el protocolo con UUID nuevos; no se deben
reutilizar sus IDs ni reejecutar la secuencia 7E.

```sh
/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/javac \
  -cp 'services/live-feed-producer/target/classes:services/match-state-service/target/classes:/private/tmp/live-jvg-7f/lib/*' \
  /private/tmp/live-jvg-7f/Robustness.java
/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java \
  -cp '/private/tmp/live-jvg-7f:services/match-state-service/target/classes:services/live-feed-producer/target/classes:/private/tmp/live-jvg-7f/lib/*' \
  Robustness
```

El ejecutor adicional se compila con el mismo classpath más
`/private/tmp/live-jvg-7f`, después de Robustness, y se ejecuta con el mismo
classpath final sustituyendo `Robustness` por `RabbitRecovery`. Sus fuentes se
conservan como [rabbit-recovery-harness-source.txt](evidence/phase-7f/rabbit-recovery-harness-source.txt).
La fuente Robustness archivada incluye un override opcional `test.rabbit.port`;
sin override conserva 5672, como en la primera ejecución completa.

El orden de clases de match-state-service primero es necesario para cargar la
configuración de consumo. El harness requiere brokers locales 9092/5672 y guest
en un entorno de pruebas; no es portable a otro entorno sin adaptar sus parámetros.

Inspecciones ejecutadas: git status/diff, rg de fuentes/docs, lectura de POM y
pruebas, docker ps, rabbitmqctl list_queues, kafka-consumer-groups --describe
del grupo 7E, y rabbitmqadmin get messages con ack_requeue_true exclusivamente
sobre las colas nuevas 7F. No se consumieron ni reencolaron los cinco mensajes 7E.

Las colas 7F se inspeccionaron con count 10/5 y sus payloads se extrajeron con
Python para conservar JSON legible. No se borraron recursos. Las colas finales
de la primera ejecución completa contienen 4 y 1 mensajes; la cola de recuperación
AMQP contiene 2; la cola del intento fallido conserva 17.

Evidencias: [carpeta phase-7f](evidence/phase-7f/), con logs finales Maven,
Surefire XML, fuente textual del harness, logs reales, resumen de aserciones,
payloads y estados finales. [sha256.json](evidence/phase-7f/sha256.json) permite
comprobar integridad. Los reintentos Maven 3.9 online interrumpidos permanecen
solo en /private/tmp; no se presentan como evidencia de pruebas exitosas.

## Conservación de 7E y estado final

La lectura final confirma cola 7E con 5 mensajes listos y 0 sin confirmar; su grupo
continúa en offset 5, log-end 5, lag 0. Se mantuvieron sus dos brokers, servicios,
partido, topic y exchange. No se repitió la prueba funcional histórica.

Cambios del repositorio en 7F: este informe y docs/evidence/phase-7f. Los builds
regeneraron target (artefactos ignorados), sin modificar fuentes ni configuración.
El informe histórico phase-7b-integration-report.md no fue editado en esta fase.
Sin commits, merges, pushes, borrados de datos/recursos ni cambios a servicios ajenos.

Los recursos 7F creados se conservaron porque no se autorizó eliminarlos. La
limpieza posterior debe limitarse a los prefijos 7F identificados y requerirá
autorización. No se solicita intervención manual para ninguna prueba ejecutable
dentro del alcance actual.

Siguiente fase propuesta: aprobar contratos y estrategia de persistencia/errores,
después implementar los cambios autorizados y probar reinicios, crash windows,
reconexión real y rebalance en brokers completamente independientes. Se termina
esta auditoría porque lo restante requiere cambios de producto/aprobaciones o
fallos de infraestructura que no deben inyectarse en los brokers de evidencia.
