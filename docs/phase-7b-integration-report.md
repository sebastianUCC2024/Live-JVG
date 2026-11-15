# Fase 7 — Informe de integración (7C)

Fecha: 2026-11-14 (America/Bogota).

Estado: bloqueos del entorno diagnosticados; pruebas reales no ejecutadas por Codex.

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
