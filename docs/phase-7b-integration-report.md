# Fase 7 — Informe de integración consolidado (7B–7D)

Fecha: 2026-11-14 (America/Bogota).

Estado actual (Fase 7E): PASS funcional de la secuencia de cinco eventos del
publicador real → Kafka → match-state-service → RabbitMQ. Publicación mediante
bean Spring desde JShell ejecutada por el usuario; registros Kafka y cinco
resultados RabbitMQ inspeccionados directamente por Codex. Ver sección 12.
El cierre general del sistema sigue pendiente: resiliencia, persistencia,
deduplicación y aprobación de contratos no quedan demostradas por esta prueba.

Las secciones 1–9 registran el diagnóstico inicial de Codex en Fase 7B; la sección
10 registra Fase 7C. Sus estados y casos «no ejecutados» corresponden a esas
intervenciones, no al resultado posterior del usuario. La sección 11 consolida
el estado de aquel momento y la procedencia de las evidencias. La sección 12
actualiza el resultado vigente. No se repitió el diagnóstico
ni se asumió acceso de Codex a los recursos de la terminal local.

## 1. Objetivo

Verificar con brokers reales el flujo live-feed-producer → Kafka →
match-state-service → RabbitMQ, generando los eventos mediante los endpoints REST
existentes del productor.

Ese requisito REST corresponde al planteamiento original de Fase 7B. La prueba
posterior utilizó publicación directa en Kafka; la ausencia de REST no impide
probar la mensajería mediante el publicador existente y no se considera una
obligación arquitectónica por sí misma.

## 2. Entorno de pruebas

- Workspace: /Users/valeriaestefaniagongoratorres/Live-JVG.
- Rama: feature/Valeria-infra-history. Árbol limpio antes de crear este informe.
- Ambos POM declaran Java 17 y Spring Boot 3.5.7.
- Maven no disponible en PATH. Se utilizó la distribución 3.9.16 ya presente en
  /Users/valeriaestefaniagongoratorres/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn.
- Para los reintentos se seleccionó Java 17 mediante JAVA_HOME.
- Docker CLI 29.8.0, darwin/arm64, contexto desktop-linux; daemon inaccesible.
- docker-compose.yml contiene `name: live-JVG` y `services: {}`.
- No se iniciaron aplicaciones, contenedores, consumidores ni colas de prueba.

## 3. Casos de prueba

Precondición funcional pendiente: partido registrado, dos equipos conocidos,
marcador inicial 0–0 y VAR CLEAR. Cada evento debe tener un ID nuevo salvo en
los casos de duplicación.

| Caso | Resultado esperado | Observado |
|---|---|---|
| GOAL local | 1–0, VAR CLEAR | No ejecutado |
| GOAL visitante | 1–1, VAR CLEAR | No ejecutado |
| VAR_CHECK del gol visitante | 1–1, VAR IN_PROGRESS | No ejecutado |
| GOAL_ANNULLED referido al gol visitante | 1–0, VAR CLEAR | No ejecutado |
| MATCH_CONTINUES | 1–0, VAR CLEAR | No ejecutado |
| Reentrega del mismo GOAL, mismo ID y contenido | Sin segundo incremento ni nueva salida si ya fue confirmada | No ejecutado |
| Reentrega de la misma anulación | Sin segundo descuento ni nueva salida si ya fue confirmada | No ejecutado |
| Evento inválido en partición aislada | Rechazo, sin cambio de estado, offset sin confirmar | No ejecutado |
| Reinicio del consumidor aislado | Documentar pérdida de estado y pendientes volátiles | No ejecutado |
| Pérdida temporal de acceso a RabbitMQ, solo en entorno aislado | Pendiente conservado y Kafka sin avanzar; recuperación sin doble efecto | No ejecutado |

## 4. Evidencias

### Inspección ejecutada

```sh
git status --short
git branch --show-current
rg --files --hidden -g AGENTS.md -g '!.git' .
cat services/live-feed-producer/src/main/java/com/live_jgv/live_feed_producer/entrypoint/rest/MatchSimulationController.java
wc -c services/live-feed-producer/src/main/java/com/live_jgv/live_feed_producer/application/service/MatchSimulationService.java contracts/events/*.json
rg -n '@RestController|@RequestMapping|@PostMapping|@GetMapping|@PutMapping|@PatchMapping' services/live-feed-producer/src/main
command -v mvn
docker version
docker ps -a
cat docker-compose.yml
```

Resultados: git status no mostró cambios y la rama fue
`feature/Valeria-infra-history`. El controlador contiene únicamente una clase
vacía, sin anotaciones. MatchSimulationService.java y los cuatro schemas
compartidos tienen 0 bytes. La búsqueda de anotaciones REST no produjo resultados.
`command -v mvn` no produjo una ruta.

Docker version y docker ps -a informaron:

```text
failed to connect to the docker API at unix:///Users/valeriaestefaniagongoratorres/.docker/run/docker.sock; check if the path is correct and if the daemon is running: dial unix /Users/valeriaestefaniagongoratorres/.docker/run/docker.sock: connect: no such file or directory
```

Esto impide inspeccionar contenedores mediante ese daemon; no demuestra que no
existan brokers accesibles por otros medios.

### Maven ejecutado por servicio

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home /Users/valeriaestefaniagongoratorres/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn -o -f services/live-feed-producer/pom.xml clean verify
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home /Users/valeriaestefaniagongoratorres/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn -o -f services/match-state-service/pom.xml clean verify
```

Ambas órdenes terminaron con código 1 durante la lectura del modelo Maven.
Fragmento común del error real:

```text
Cannot access central (https://repo.maven.apache.org/maven2) in offline mode and the artifact org.springframework.boot:spring-boot-starter-parent:pom:3.5.7 has not been downloaded from it before.
```

| Servicio | Pruebas ejecutadas | Fallos/errores de pruebas | Error de build |
|---|---:|---|---|
| live-feed-producer | 0 | Sin reporte Surefire | Parent POM no resoluble |
| match-state-service | 0 | Sin reporte Surefire | Parent POM no resoluble |

No se generaron evidencias de ejecución Kafka/RabbitMQ: no hay mensajes
capturados, confirmaciones reales, offsets observados ni logs de procesamiento.
No se utilizaron Mockito ni confirmaciones simuladas.

## 5. Resultados

La condición de entrada no se cumple. No se puede afirmar que el flujo funciona
ni que haya fallado una prueba funcional: esas pruebas no se ejecutaron.

La revisión estática de Fase 7A identificó coincidencia provisional de topic
match_events, clave matchId, snake_case, cuatro tipos de evento y
reference_event_id. La salida configura exchange live_updates tipo topic durable
y routing key score.{matchId}. Esto no sustituye una comprobación con brokers ni
la aprobación de los contratos compartidos, que siguen vacíos.

## 6. Problemas y soluciones pendientes

1. Dependencia crítica ausente: habilitar resolución de dependencias o aportar una
   caché completa; repetir ambos clean verify con Java 17.
2. No existen endpoints del simulador: identificar una rama que los implemente y
   revisar su integración, o autorizar su implementación como tarea separada.
   No se sustituyó la vía REST exigida por JShell ni por rutas inventadas.
3. Docker inaccesible: disponer de un daemon operativo o aportar conexiones a
   brokers locales existentes. Compose no provisiona ningún servicio.
4. Contratos compartidos vacíos: aprobar con el equipo las propuestas y verificar
   compatibilidad con consumidores de DEV 2 y DEV 3.

No se aplicaron correcciones de código, infraestructura o contratos. Solo se
creó este informe. No se instalaron herramientas ni se hicieron merges, commits
o push.

## 7. Limitaciones de idempotencia y persistencia

Según el código inspeccionado en Fase 7A, estado, historial de deduplicación y
outbox residen en memoria. El reinicio los pierde; los offsets Kafka confirmados
no reconstruyen automáticamente el marcador. La deduplicación depende de
conservar ese estado y de reenviar el mismo ID con el mismo contenido.

La confirmación RabbitMQ y la eliminación del pendiente no constituyen una
transacción distribuida: un resultado incierto puede producir reentrega. Los
consumidores RabbitMQ deben deduplicar por (match_id, event_id). Los reintentos
indefinidos de mensajes inválidos pueden bloquear una partición. Estos límites
son observaciones estáticas, no resultados de resiliencia ejecutados en Fase 7B.

## 8. Conclusiones

Fase 7B no iniciada por prerrequisitos incumplidos. Ningún caso funcional o de
resiliencia está aprobado. Se requiere resolver los bloqueos antes de ejecutar.

## 9. Pendientes para integración general

- DEV 1: aportar simulador REST, aprobar semántica de eventos y repetir builds.
- DEV 2: confirmar contrato ScoreUpdate, colas/bindings y deduplicación.
- DEV 3: aportar brokers locales verificables, configuración Kafka (incluida la
  necesidad de ZooKeeper), retención, permisos y estrategia de persistencia/replay.
- Equipo: proporcionar partido/equipos de prueba, aprobar contratos y permitir
  una cola temporal y un grupo Kafka exclusivo antes de publicar eventos.
- Tras resolverlos: observar cada entrada Kafka y salida RabbitMQ, correlacionar
  IDs/referencias/timestamps y ejecutar resiliencia solo en recursos aislados.

Trabajo detenido a la espera de aprobación; sin cambios a Docker Compose ni a
servicios de DEV 2/DEV 3.

## 10. Fase 7C — Resolución de bloqueos (2026-11-14)

Esta sección actualiza el diagnóstico anterior; los resultados de Fase 7B se
conservan como registro histórico. No se ejecutaron pruebas reales en Fase 7C.

### Estado de bloqueos

| Bloqueo | Estado | Resultado / acción pendiente |
|---|---|---|
| Maven ausente en PATH | RESUELTO | Se utilizó Maven 3.9.16 ya instalado, añadiéndolo al PATH solo del proceso |
| Java predeterminado 25 | RESUELTO | Se seleccionó Java 17.0.20.1 mediante JAVA_HOME solo del proceso |
| Caché Maven predeterminada sin permisos de escritura | RESUELTO | Reintento con caché temporal escribible, sin borrar ni modificar la caché global |
| Parent/dependencias no descargados | REQUIERE INTERVENCIÓN DEL USUARIO | Maven Central no resuelve por DNS desde el entorno; repetir desde terminal local con conectividad |
| Daemon Docker inaccesible | REQUIERE INTERVENCIÓN DEL USUARIO | No existen los sockets de desktop-linux ni default; verificar Desktop desde terminal local |
| Simulador sin endpoints REST | PENDIENTE | Confirmado tras leer todas sus clases; alternativa preparada con el publicador existente, sin implementar rutas |
| Contratos compartidos vacíos | PENDIENTE | Aprobación del equipo; no se modificaron |
| Flujo Kafka → RabbitMQ sin ejecutar | PENDIENTE | Requiere builds, brokers accesibles y recursos de prueba definidos |

### Diagnóstico Maven y comandos reales

Se ejecutaron `java -version`, `mvn -version` y `mvn help` en el entorno original.
Java informó Microsoft OpenJDK 25.0.4; las dos órdenes Maven fallaron por comando
no encontrado. Después se seleccionaron herramientas existentes:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export PATH=/Users/valeriaestefaniagongoratorres/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin:$JAVA_HOME/bin:$PATH
mvn -version
mvn help
mvn -f services/live-feed-producer/pom.xml clean verify
mvn -f services/match-state-service/pom.xml clean verify
```

`mvn -version`: Maven 3.9.16, Java 17.0.20.1 Eclipse Adoptium, macOS arm64.
`mvn help`: código 1, `Unknown lifecycle phase "help"`. Es un error de sintaxis
del comando solicitado, no una prueba de conectividad. La ayuda de CLI es
`mvn --help`; los objetivos del plugin Help usan `help:...`.

Ambos clean verify terminaron con código 1: no se puede escribir bajo
`~/.m2/repository/.../spring-boot-starter-parent/3.5.7` (`Operation not permitted`).
Se repitieron sin modo offline y con una caché escribible:

```sh
mvn -U -Dmaven.repo.local=/private/tmp/live-jvg-phase7c-m2 -f services/live-feed-producer/pom.xml clean verify
mvn -U -Dmaven.repo.local=/private/tmp/live-jvg-phase7c-m2 -f services/match-state-service/pom.xml clean verify
```

Ambos reintentos terminaron con código 1 por el error real:

```text
Could not transfer artifact org.springframework.boot:spring-boot-starter-parent:pom:3.5.7 from/to central (https://repo.maven.apache.org/maven2): repo.maven.apache.org: nodename nor servname provided, or not known
```

También se ejecutó:

```sh
curl -I --connect-timeout 5 --max-time 10 https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/3.5.7/spring-boot-starter-parent-3.5.7.pom
```

Resultado: código 6, `Could not resolve host: repo.maven.apache.org`. No se pudo
verificar la disponibilidad del artefacto remoto. No se cambió Spring Boot.

Configuración inspeccionada:

- Ambos POM usan parent 3.5.7, relativePath vacío y Java 17. No declaran
  repositorios ni pluginRepositories propios; Maven intenta Central por HTTPS.
- No existe ~/.m2/settings.xml ni .mvn/maven.config en raíz o ambos servicios.
- El settings.xml global de Maven no tiene proxy activo ni offline activo. Solo
  contiene el mirror estándar que bloquea repositorios HTTP externos; no bloquea
  el HTTPS de Central. No se encontraron perfiles globales activos personalizados.
- HTTP_PROXY, HTTPS_PROXY, ALL_PROXY, NO_PROXY, MAVEN_ARGS, MAVEN_OPTS y
  JAVA_TOOL_OPTIONS no están definidas. No hay evidencia de proxy configurado.
- El parent 3.5.7 no existe en la caché predeterminada. No se eliminó caché alguna.
- El modo offline de Fase 7B provenía de `-o`; Fase 7C intentó resolución online.
- Los wrappers de ambos servicios apuntan a Maven 3.10.0. No se ejecutaron para
  evitar descargar otra distribución; se usó la 3.9.16 existente sin editar wrappers.

| Servicio | Resultado final actual | Pruebas | Fallos / errores de pruebas |
|---|---|---:|---|
| live-feed-producer | Código 1, resolución DNS de Central | 0 | No disponibles: no se alcanzó Surefire |
| match-state-service | Código 1, resolución DNS de Central | 0 | No disponibles: no se alcanzó Surefire |

### Diagnóstico Docker y brokers

Comandos ejecutados: `docker version`, `docker info`, `docker compose config`,
`docker compose ps`, `docker context show`, `docker context ls`,
`docker context inspect desktop-linux` y `docker desktop status`.

- version, info y compose ps fallan al conectar con el socket inexistente de
  desktop-linux. No es un error de permisos del socket: ese archivo no existe.
- compose config funciona y devuelve `name: live-jvg` y `services: {}`.
- Contexto activo: desktop-linux. El contexto default apunta a
  /var/run/docker.sock, que tampoco existe. No hay evidencia de un contexto
  alternativo operativo; no se cambió el contexto global.
- DOCKER_HOST y DOCKER_CONTEXT no están definidas.
- desktop status no pudo obtener el estado; además informó falta de permisos
  para crear su log bajo Library/Containers. La consulta finalizó con código 1.
  No se puede concluir desde aquí si Desktop está apagado o si el aislamiento
  impide observarlo. No se inició ni reinició Desktop.
- KAFKA_BOOTSTRAP_SERVERS, RABBITMQ_HOST y RABBITMQ_PORT no están definidas.
- Intentos TCP Python a localhost:9092, 5672, 2181 y 15672 fallaron con
  `PermissionError: [Errno 1] Operation not permitted`. Esto demuestra restricción
  del entorno, no ausencia de brokers ni puertos cerrados.
- No se verificaron topic, exchange, autenticación ni salud de brokers.

### Revisión completa del productor y contratos

Se leyeron todas las clases Java de producción y pruebas del productor, sus
properties, POM, wrapper y propuestas. El único controlador es una clase vacía;
MatchSimulationService.java tiene 0 bytes. No hay rutas REST del simulador ni
invocaciones productivas que automaticen la generación de eventos.

Sí existe el puerto `MatchEventPublisher.publish(MatchEvent)` y el componente
`KafkaMatchEventPublisher`, que serializa una sola vez, usa StringSerializer y
envía `template.send(topic, event.matchId(), payload)`. Devuelve un futuro que
completa tras la confirmación Kafka, con acks=all e idempotencia del productor.
Las pruebas existentes cubren dominio, contrato estructural, contexto y adaptador
con KafkaTemplate simulado; no equivalen a pruebas reales.

| Campo Kafka | Compatibilidad observada por lectura |
|---|---|
| event_id | String no vacío en ambos modelos; create genera UUID, el consumidor no exige formato UUID |
| match_id | String no vacío; el listener exige igualdad con clave Kafka |
| event_type | Los cuatro enums coinciden |
| team_id | Obligatorio/no vacío en GOAL; opcional/null en otros eventos; el consumidor exige pertenencia al partido si está presente |
| minute | Entero obligatorio de 0 a 130; el consumidor rechaza ausencia, null y fracciones |
| timestamp | Instant serializado como texto ISO 8601; consumidor usa Instant.parse |
| reference_event_id | Obligatorio/no vacío para GOAL_ANNULLED; opcional/null para otros tipos; el dominio valida existencia del gol y pertenencia |

Ambos adaptadores usan snake_case. El consumidor rechaza campos desconocidos,
claves duplicadas, tokens adicionales y coerciones. No se encontró un desajuste
de nombres/tipos para eventos válidos de la secuencia controlada.

Hay una diferencia real de validación: productor y propuesta permiten, fuera de
GOAL, team_id vacío o ajeno al partido; el consumidor lo rechaza si está presente.
La propuesta tampoco comprueba que una referencia opcional de VAR_CHECK designe
un gol activo; el dominio sí. Son restricciones semánticas adicionales, por lo
que no todo evento construible por el productor es aceptable por el consumidor.

La salida ScoreUpdate contiene match_id, home_score, away_score, event_id,
event_type, updated_at, var_status y reference_event_id. Coincide con la propuesta
para el flujo normal. updated_at es hora de procesamiento, no timestamp del
evento. event_id conserva el ID de entrada; message_id AMQP usa ese mismo ID.
Los mensajes son JSON UTF-8 persistentes hacia live_updates (topic durable), con
score.{matchId}, mandatory y confirms correlacionados. El publisher espera ack y
rechaza retornos antes de completar el procesamiento Kafka.

Los cuatro schemas compartidos siguen vacíos. Pendientes de aprobación: schemas
definitivos, nulls, límites de minute, semántica de referencias/VAR, deduplicación
y timestamps. No se puede afirmar compatibilidad con consumidores DEV 2/DEV 3
sin acordar sus contratos de frontera.

### Alternativa menos invasiva y preparación real (NO EJECUTADA)

Publicar directamente en Kafka sí permitiría comprobar Kafka → match-state →
RabbitMQ. Un console producer evitaría probar el adaptador de live-feed-producer.
La alternativa recomendada es invocar el bean existente desde JShell o un scratch
de IDE: prueba todo el flujo de mensajería sin modificar código ni añadir REST.
No satisface por sí sola la condición REST original de Fase 7B; esa condición
requiere endpoints implementados o aceptar expresamente esta alternativa.

Una vez resueltos los builds y accesos:

1. Verificar `match_events` con la herramienta Kafka del entorno y describir
   particiones/configuración. No crear ni alterar topics compartidos a ciegas.
2. Verificar en RabbitMQ `live_updates`, tipo topic y durable. Si ya existe con
   otro tipo, no borrarlo; resolver con el responsable de infraestructura.
3. Con permisos, crear una cola temporal exclusiva, enlazarla con
   `score.phase7c-smoke` y observarla mediante consumidor AMQP o consola RabbitMQ
   Management si está habilitada. No consumir colas de otros servicios. La cola
   debe existir y estar enlazada antes del primer evento para evitar retornos.
4. Configurar MATCH_ID=phase7c-smoke, HOME_TEAM_ID=home y AWAY_TEAM_ID=away, brokers
   y credenciales del entorno. Usar un grupo Kafka exclusivo y confirmar la
   asignación antes de publicar. No resetear offsets de grupos compartidos.
5. Observar Kafka con claves y timestamps impresos mediante un consumidor de
   prueba exclusivo. Correlacionar cada ID con logs del listener y salida AMQP.
6. Invocar el publicador real y esperar cada futuro antes de publicar el siguiente.
   Usar IDs nuevos por ejecución, mantener los objetos para probar duplicados.

Preparar classpath del productor en Windows PowerShell, después de un build válido:

```powershell
mvn -f services/live-feed-producer/pom.xml dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
$producerRoot = (Resolve-Path services/live-feed-producer).Path
$runtimeClasspath = (Get-Content "$producerRoot/target/runtime-classpath.txt" -Raw).Trim()
jshell --class-path "$producerRoot/target/classes;$runtimeClasspath"
```

Secuencia JShell preparada, sin resultados observados:

```java
import com.live_jgv.live_feed_producer.LiveFeedProducerApplication;
import com.live_jgv.live_feed_producer.domain.model.*;
import com.live_jgv.live_feed_producer.domain.port.MatchEventPublisher;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
var context = new SpringApplicationBuilder(LiveFeedProducerApplication.class).web(WebApplicationType.NONE).run();
var publisher = context.getBean(MatchEventPublisher.class);
var homeGoal = MatchEvent.create("phase7c-smoke", EventType.GOAL, "home", 10, Instant.now(), null);
publisher.publish(homeGoal).get(30, TimeUnit.SECONDS);
var awayGoal = MatchEvent.create("phase7c-smoke", EventType.GOAL, "away", 20, Instant.now(), null);
publisher.publish(awayGoal).get(30, TimeUnit.SECONDS);
var review = MatchEvent.create("phase7c-smoke", EventType.VAR_CHECK, null, 21, Instant.now(), awayGoal.eventId());
publisher.publish(review).get(30, TimeUnit.SECONDS);
var annulment = MatchEvent.create("phase7c-smoke", EventType.GOAL_ANNULLED, null, 22, Instant.now(), awayGoal.eventId());
publisher.publish(annulment).get(30, TimeUnit.SECONDS);
var continuation = MatchEvent.create("phase7c-smoke", EventType.MATCH_CONTINUES, null, 23, Instant.now(), null);
publisher.publish(continuation).get(30, TimeUnit.SECONDS);
context.close();
```

Esperados: 1–0 CLEAR, 1–1 CLEAR, 1–1 IN_PROGRESS, 1–0 CLEAR y 1–0 CLEAR,
respectivamente. Verificar la salida después de cada publicación; un futuro Kafka
completado no demuestra procesamiento ni entrega RabbitMQ. Un timeout tampoco
demuestra ausencia de entrega. No se ejecutó esta secuencia ni resiliencia.

### Comandos para la terminal local del usuario

Desde C:\Live-JVG, seleccionar JAVA_HOME de un JDK 17 instalado y asegurarse de
que `mvn -version` lo confirme. No usar modo offline:

```powershell
java -version
mvn -version
mvn --help
Resolve-DnsName repo.maven.apache.org
curl.exe -I https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/3.5.7/spring-boot-starter-parent-3.5.7.pom
mvn -U -f services/live-feed-producer/pom.xml clean verify
mvn -U -f services/match-state-service/pom.xml clean verify
docker version
docker info
docker context ls
docker compose config
docker compose ps
docker ps -a
Test-NetConnection localhost -Port 9092
Test-NetConnection localhost -Port 5672
```

Si Maven no puede escribir en su caché, añadir
`-Dmaven.repo.local=C:/Live-JVG/.m2/repository` a los builds. Si DNS/proxy falla,
revisar la conectividad local o la configuración corporativa autorizada; no
desactivar TLS ni cambiar Spring Boot para evitar el diagnóstico.

En macOS, seleccionar Java 17 con `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`
y ejecutar los mismos builds desde la raíz del checkout. Usar `curl` y
`dscacheutil -q host -a name repo.maven.apache.org` para la comprobación de red.

Si Desktop está detenido, abrir Docker Desktop y esperar a que `docker info`
muestre Server. Verificar el contexto esperado por la instalación local, sin
cambiarlo a uno supuesto. Un TCP abierto no comprueba protocolo/autenticación.
Compose vacío no puede iniciar los brokers; DEV 3 debe aportar la infraestructura
existente o una propuesta cuya modificación sea autorizada por separado.

### Evidencias y archivos modificados

Logs reales en [evidence/phase-7c](evidence/phase-7c/):

- maven-version.txt y maven-help.txt.
- live-feed-producer-verify.txt y match-state-service-verify.txt (caché global).
- live-feed-producer-writable-cache.txt y match-state-service-writable-cache.txt
  (reintentos online; error DNS).
- central-connectivity.txt, docker-version.txt, docker-info.txt,
  compose-config.txt, compose-ps.txt y broker-connectivity.txt.

Cambios de repositorio: únicamente este informe actualizado y la carpeta de
evidencias. Se creó una caché temporal de diagnóstico en /private/tmp; no se
modificó configuración global. Sin cambios en código, POM, wrappers, contratos,
Compose ni servicios ajenos. Sin commits, merges o push.

Siguiente paso: resolver acceso local a Central y al daemon/brokers, repetir
builds, acordar contratos/recursos y aprobar la vía de publicación antes de
retomar integración. Trabajo detenido a la espera de aprobación.

## 11. Fase 7D — Consolidación de resultados posteriores del usuario

### Procedencia y alcance de las evidencias

| Categoría | Evidencia disponible | Alcance |
|---|---|---|
| Ejecutado por Codex | Inspección estática y logs locales de Fases 7B/7C en evidence/phase-7c | Diagnóstico de aquel entorno; no contiene una ejecución real con brokers |
| Ejecutado por el usuario en macOS | Resultados comunicados expresamente en la solicitud de Fase 7D | Builds exitosos, contenedores temporales, secuencia real de tres eventos, inspección AMQP y limpieza |
| Verificado por Codex mediante salidas crudas de terminal compartidas | No se adjuntaron salidas completas de la ejecución posterior en el material de esta fase | No se atribuye verificación independiente de esos logs; los valores comunicados se conservan como resultados reportados |
| No ejecutado en la secuencia posterior reportada | Publicador live-feed-producer → Kafka, MATCH_CONTINUES y pruebas adicionales indicadas abajo | No se declara integración completa ni se extrapola éxito |

El usuario informó que inspeccionó los tres mensajes en RabbitMQ mediante
`rabbitmqadmin get messages`. Esa observación se registra con su atribución;
no se crea una captura, transcripción de terminal ni payload completo sintético.
Los archivos de evidencia 7C siguen documentando fallos históricos de Codex, no
los éxitos posteriores del usuario. No se conocen los comandos completos de
publicación, payloads de entrada completos, timestamps exactos ni conteos del
productor; no se reconstruyen como si fueran evidencia capturada.

### Maven y Java en la terminal local

- Java 17 disponible y Maven instalado/operativo, según el usuario.
- `mvn -f services/live-feed-producer/pom.xml clean verify`: exitoso reportado;
  cantidad de pruebas, fallos, errores y omisiones no desglosados en el material
  proporcionado. No se reutiliza el conteo histórico de 43 como resultado actual.
- `mvn -f services/match-state-service/pom.xml clean verify`: exitoso reportado;
  73 pruebas sin fallos. Errores y omisiones no desglosados explícitamente.

Estos builds resuelven el bloqueo de ejecución local. No prueban que hayan
cambiado el DNS, los permisos o el acceso a Docker del entorno de Codex.

### Infraestructura temporal utilizada por el usuario

| Recurso | Configuración reportada |
|---|---|
| Kafka | apache/kafka:3.9.1 |
| RabbitMQ | rabbitmq:4-management |
| Topic | match_events |
| Grupo consumidor | match-state-service |
| Exchange RabbitMQ | live_updates, tipo topic |
| Cola de observación | score_test_queue |
| Routing key | score.match-001 |

No se atribuyen puertos, nombres de contenedor, particiones, retención, configuración
de ZooKeeper o propiedades de durabilidad no comunicadas. El proyecto utilizó
un Compose temporal externo al repositorio, `/tmp/live-jvg-compose.yml`.

### Prueba real ejecutada por el usuario

Flujo efectivamente cubierto: kafka-console-producer.sh → Kafka →
match-state-service → RabbitMQ → inspección de score_test_queue.
La inyección directa evita el adaptador de live-feed-producer; el build exitoso
de ese servicio no completa su validación de transporte.

| Evento | ID de entrada reportado | Esperado para esta secuencia | Observado por el usuario |
|---|---|---|---|
| GOAL local | goal-test-001 | 1–0, CLEAR | 1–0, CLEAR |
| VAR_CHECK | var-check-001 | 1–0, IN_PROGRESS | 1–0, IN_PROGRESS |
| GOAL_ANNULLED | goal-annulled-001 | 0–0, CLEAR | 0–0, CLEAR |

Los tres mensajes se inspeccionaron en score_test_queue con
`rabbitmqadmin get messages`, según el usuario. Del último mensaje se comunicaron
estos campos (extracto de valores reportados, no captura literal):

| Campo | Valor |
|---|---|
| match_id | match-001 |
| home_score | 0 |
| away_score | 0 |
| event_type | GOAL_ANNULLED |
| var_status | CLEAR |
| reference_event_id | goal-test-001 |

Se confirma a nivel de reporte la anulación del gol original y el retorno a 0–0.
No hay evidencia cruda adicional para verificar independientemente clave Kafka,
timestamps, partición/offset de cada evento válido o todos los campos de los tres
mensajes. La coincidencia estática de esos campos está documentada en Fase 7C.

### Incidencia y limpieza reportadas

El primer mensaje Kafka tenía formato incorrecto y quedó en offset 0. El usuario
detuvo el consumidor, ajustó el offset del grupo match-state-service a 1 y reinició
el microservicio. Después se procesó el evento válido.

Este procedimiento omitió el mensaje inválido en el entorno temporal; no lo
reparó ni demuestra recuperación automática, cuarentena o replay. Tampoco es una
prueba de persistencia ante reinicio de un estado ya procesado. No se reproduce
el comando de ajuste porque no fue proporcionado. No se recomienda aplicar ese
salto de offsets a un grupo compartido sin un procedimiento acordado.

Al finalizar, el usuario ejecutó:

```sh
docker compose -p live-jvg-test -f /tmp/live-jvg-compose.yml down
```

Reportó eliminación de los dos contenedores temporales y de la red Docker.
No se afirma eliminación de volúmenes ni imágenes. Codex no ejecutó la limpieza.
La infraestructura de prueba ya no queda disponible como consecuencia de esta
limpieza reportada.

### Tabla final de bloqueos y pendientes

| Elemento | Estado actual | Evidencia / condición restante |
|---|---|---|
| Java 17 y Maven local | RESUELTO | Disponibilidad y builds exitosos reportados por el usuario |
| Dependencias de ambos servicios | RESUELTO en terminal local | clean verify exitoso reportado; sin nueva verificación en Codex |
| Docker y brokers locales | RESUELTO para la prueba realizada | Contenedores reales utilizados y luego retirados |
| Kafka → match-state-service → RabbitMQ | RESUELTO para los tres eventos reportados | GOAL, VAR_CHECK y GOAL_ANNULLED observados en cola |
| Mensaje inválido en offset 0 | RESUELTO operativamente en esa prueba | Salto manual a offset 1; política general de errores sigue pendiente |
| Endpoints REST del simulador | PENDIENTE como funcionalidad, no requisito obligatorio de mensajería | No existen; se puede validar el publicador por un mecanismo directo |
| Contratos compartidos | PENDIENTE | Aprobación del equipo; schemas siguen vacíos según inspección previa |
| live-feed-producer → Kafka | PENDIENTE | Publicar con el adaptador real y observar la cadena completa |
| MATCH_CONTINUES | PENDIENTE | No se incluyó en la secuencia ejecutada |
| Duplicados y resiliencia | PENDIENTE | No se reportaron duplicados de gol/anulación, caída RabbitMQ ni recuperación posterior |
| Evidencia cruda de ejecución local | PENDIENTE | Incorporar salidas originales disponibles para revisión independiente |

No se reabre como bloqueo local el fallo de acceso de Codex: son entornos
distintos. Los recursos temporales pueden recrearse para completar los casos
pendientes mediante el procedimiento de infraestructura del equipo.

### Condiciones para cerrar la integración completa de Fase 7

1. Ejecutar una secuencia usando el bean/adaptador real de live-feed-producer
   contra Kafka y observar su consumo y salida RabbitMQ; REST es opcional para
   esta validación. Registrar clave matchId, topic, IDs, JSON snake_case,
   timestamps y referencias junto a las salidas correlacionadas.
2. Completar MATCH_CONTINUES y el GOAL visitante de la secuencia original,
   comprobando marcador y VAR después de cada evento.
3. Ejecutar y registrar duplicados de gol/anulación, entradas inválidas y
   recuperación de RabbitMQ en infraestructura aislada. Evaluar reinicios con
   estado previo y documentar el límite del almacenamiento volátil. No equiparar
   el reinicio tras saltar un mensaje inválido con esa prueba.
4. Aprobar los contratos de entrada/salida con DEV 2/DEV 3 y acordar colas,
   bindings, deduplicación, tratamiento de errores y recuperación. El éxito de
   la cola de prueba no valida automáticamente consumidores de otros servicios.
5. Incorporar las salidas de terminal originales disponibles, los conteos de
   ambos builds y los mensajes observados, sin reconstruir logs faltantes.
   Documentar cualquier caso que el equipo excluya expresamente del cierre.

Conclusión de alcance: la comunicación Kafka → match-state-service → RabbitMQ
queda respaldada por los resultados locales reportados para tres eventos. La
integración completa de los dos microservicios permanece abierta por los puntos
anteriores. Persistencia durable y garantías ante reinicios siguen siendo límites
del diseño actual; cualquier aceptación para uso más amplio debe explicitarlos.

En Fase 7D Codex solo modificó `docs/phase-7b-integration-report.md`. No ejecutó
builds, diagnóstico de infraestructura ni pruebas de mensajería; no modificó
código, contratos, Compose o dependencias. Sin commits, merges o push.
Trabajo detenido a la espera de aprobación del usuario.

## 12. FASE 7E — Productor real → Kafka → match-state-service → RabbitMQ

### Objetivo, procedencia y alcance

Continuación desde Paso 25, sin reiniciar la secuencia ni publicar eventos.
El usuario confirmó que invocó el bean real KafkaMatchEventPublisher desde JShell
a través de MatchEventPublisher, y aportó IDs, offsets y el log de confirmación
del quinto evento. Codex no ejecutó esas publicaciones; verificó los registros
existentes en Kafka y los cinco resultados reales en RabbitMQ.

Los timestamps observados son del 2026-11-15 UTC (2026-11-14 en America/Bogota).
Los estados pendientes de productor, MATCH_CONTINUES y gol visitante indicados
en Fase 7D quedan superados para esta secuencia, no para todos los escenarios.

### Infraestructura observada

- Contenedores activos: live-jvg-test-kafka-1 (apache/kafka:3.9.1) y
  live-jvg-test-rabbitmq-1 (rabbitmq:4-management), proyecto live-jvg-test.
- Topic: match_events, partición inspeccionada: 0.
- Partido: phase7e-f020cbf4-34f4-4514-b0ea-afac276164dc.
- Grupo observado: phase7e-f020cbf4-34f4-4514-b0ea-afac276164dc-state.
- Exchange de los mensajes: live_updates.
- Routing key: score.phase7e-f020cbf4-34f4-4514-b0ea-afac276164dc.
- Cola: phase7e-f020cbf4-34f4-4514-b0ea-afac276164dc-queue.

### Paso 25 y tabla de resultados

PASS: se encontró el GOAL visitante con event_id
655071f7-955a-4a54-a615-6f4e808ecd27, home_score=0, away_score=1,
var_status=CLEAR, reference_event_id=null y updated_at presente.

| Evento | event_id | Offset Kafka | Marcador RabbitMQ | VAR RabbitMQ | Estado |
|---|---|---:|---|---|---|
| GOAL local | 80cf9464-5c02-42cb-a389-b98a4ec2e576 | 0 | 1–0 | CLEAR | PASS |
| VAR_CHECK | 58304ff4-c781-45d5-8a1f-102e5ec0cb5c | 1 | 1–0 | IN_PROGRESS | PASS |
| GOAL_ANNULLED | d74a21a7-11b2-4ab4-acc8-508f2d31f63a | 2 | 0–0 | CLEAR | PASS |
| MATCH_CONTINUES | 84ecd1fc-0e99-4f27-8031-235888acb919 | 3 | 0–0 | CLEAR | PASS |
| GOAL visitante | 655071f7-955a-4a54-a615-6f4e808ecd27 | 4 | 0–1 | CLEAR | PASS |

Codex observó cinco mensajes y cinco event_id distintos, todos con el match_id,
exchange y routing key anteriores. VAR_CHECK y GOAL_ANNULLED contienen
reference_event_id=80cf9464-5c02-42cb-a389-b98a4ec2e576 tanto en Kafka como en
RabbitMQ. Los otros tres mensajes contienen referencia null.

Kafka mostró claves iguales a match_id, JSON snake_case, minute 10, 11, 12, 13
y 20; team_id del GOAL local termina en -home y el visitante en -away. Los tres
eventos intermedios tienen team_id=null. Los cinco registros conservan timestamp
textual ISO 8601 y los IDs de la tabla.

### Extractos reales de RabbitMQ

updated_at observados, en orden de la tabla:

```text
2026-11-15T02:19:37.600179Z
2026-11-15T02:20:23.618912Z
2026-11-15T02:23:03.197620Z
2026-11-15T02:24:59.891291Z
2026-11-15T02:25:52.191806Z
```

Payload completo del quinto mensaje observado:

```json
{"match_id":"phase7e-f020cbf4-34f4-4514-b0ea-afac276164dc","home_score":0,"away_score":1,"event_id":"655071f7-955a-4a54-a615-6f4e808ecd27","event_type":"GOAL","updated_at":"2026-11-15T02:25:52.191806Z","var_status":"CLEAR","reference_event_id":null}
```

La primera consulta mostró redelivered=true para los cuatro primeros y false
para el quinto. Una segunda consulta con reencolado mostró true para todos.
Esto es consecuencia compatible con ack_requeue_true; no demuestra duplicación
del productor. No se publicó ningún evento adicional ni se borraron mensajes.
La inspección posterior de la cola mostró messages_ready=5 y
messages_unacknowledged=0.

### Comandos ejecutados y acceso

El primer intento de Paso 25 desde el sandbox terminó con código 1:
`permission denied while trying to connect to the docker API`. El mismo comando
con acceso ampliado al daemon terminó con código 0:

```sh
docker exec live-jvg-test-rabbitmq-1 rabbitmqadmin get messages \
  --queue phase7e-f020cbf4-34f4-4514-b0ea-afac276164dc-queue \
  --count 5 --ack-mode ack_requeue_true
```

Se repitió una vez, usando Python para extraer los cinco payloads JSON de la
tabla, exchange, routing_key y redelivered, y contar IDs únicos. Resultado:
`unique_event_ids=5 messages=5`. La repetición mantuvo ack_requeue_true.

Otras inspecciones ejecutadas, sin alterar offsets del grupo consumidor:

```sh
docker ps --format '{{.Names}} {{.Image}} {{.Status}}'
ps -axo pid,comm | rg -i 'java|jshell'
docker exec live-jvg-test-rabbitmq-1 rabbitmqctl list_queues name messages_ready messages_unacknowledged
docker exec live-jvg-test-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
docker exec live-jvg-test-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group phase7e-f020cbf4-34f4-4514-b0ea-afac276164dc-state
docker exec live-jvg-test-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic match_events --partition 0 --offset 0 --max-messages 5 --timeout-ms 10000 --property print.key=true --property print.offset=true --property print.timestamp=true
docker logs --tail 30 live-jvg-test-kafka-1
lsof -a -p 68834 -d 1,2
```

El lector Kafka terminó con `Processed a total of 5 messages`; leyó offsets 0–4
sin utilizar el grupo de la aplicación. La descripción del grupo mostró
CURRENT-OFFSET=5, LOG-END-OFFSET=5, LAG=0 y un consumidor activo.

Se consultaron git status, el informe y nombres de archivos de logs bajo el
repositorio y /private/tmp, sin encontrar una captura de logs de la aplicación.
Los logs del broker muestran asignación del grupo y rebalances previos; no son
logs del listener ni prueban ausencia de errores de aplicación.

### Logs del consumidor: comprobación no disponible

Los descriptores stdout/stderr del proceso Java PID 68834 apuntan a
/dev/ttys003. No se dispone de una captura histórica de esa terminal ni se leyó
su dispositivo para evitar interferir con la sesión del usuario. Por tanto,
no se verificó retrospectivamente la ausencia de errores/excepciones del
match-state-service. El lag 0 y los cinco resultados correctos demuestran el
progreso y los efectos observados, no ausencia absoluta de errores previos.

### Resultado global y pendientes

**FASE 7E: PASS para la secuencia funcional de cinco eventos.** Se correlacionaron
los cinco registros Kafka existentes con cinco salidas RabbitMQ y el resultado
final 0–1 CLEAR. La invocación del bean real es la ejecución confirmada por el
usuario; las lecturas Kafka/RabbitMQ son verificaciones directas de Codex.
La revisión histórica de logs del consumidor queda expresamente no disponible;
el PASS no incluye esa revisión ni la validación completa del sistema.

Siguen pendientes: aprobación de contratos compartidos; deduplicación ante
reenvíos; persistencia y reinicios con estado previo; fallos RabbitMQ y su
recuperación; tratamiento de entradas inválidas y replay; integración con
consumidores del resto del equipo. Esta prueba no demuestra endpoints HTTP,
simulación automática ni garantías de entrega exactamente una vez.

Único archivo modificado en esta continuación:
docs/phase-7b-integration-report.md. Sin cambios de código, schemas, POM, Compose
u otra documentación. Sin publicación, borrado de mensajes, resets de offsets,
reinicios, eliminación de recursos, commits, merges ni pushes. Los contenedores
y servicios se conservaron en ejecución. Trabajo detenido para revisión.
