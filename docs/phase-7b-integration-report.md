# Fase 7 — Informe de integración (7A–7B)

Fecha: 2026-11-14 (America/Bogota).

Estado: diagnóstico inicial; ejecución real bloqueada. Las secciones siguientes registran lo observado por Codex en ese entorno.

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
