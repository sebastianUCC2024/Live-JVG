# Fase 6: Kafka → estado del partido → RabbitMQ

Estado: implementación lista para revisión local. Contratos provisionales y
almacenamiento volátil; no ofrece recuperación automática después de reiniciar.
No se implementa la Fase 7.

## Arquitectura y alcance

```text
Kafka match_events (key = matchId)
  → KafkaMatchEventListener: JSON estricto → MatchEvent
  → ProcessAndPublishMatchEvent: entrega de pendientes y coordinación
  → MatchStateService: caso de uso existente
  → MatchState.apply: reglas de marcador y VAR existentes
  → MatchStateRepository.compareAndSet: estado + historial + salida pendiente
  → ScoreUpdatePublisher
  → RabbitScoreUpdatePublisher: JSON + mandatory + publisher confirm
  → exchange topic live_updates / score.{matchId}
  → retorno exitoso del listener → commit del offset Kafka
```

El dominio conserva independencia de Spring, Kafka y AMQP. El listener solo
deserializa, verifica la correspondencia entre clave y matchId, y delega al puerto
ProcessMatchEvent. No contiene reglas VAR. El coordinador utiliza el caso de uso
de la Fase 5; no vuelve a implementar sus reglas.

Se amplía compareAndSet con ScoreUpdate para que el adaptador guarde el nuevo
estado, el historial de deduplicación y su salida pendiente en una sola operación.
MatchStateService genera esa salida con Clock; el instante corresponde al momento
de procesamiento, no al timestamp original del evento. Los tests existentes del
servicio se adaptan a la firma ampliada conservando sus escenarios y aserciones.

InMemoryMatchStateStore implementa MatchStateRepository y ScoreUpdateOutbox.
Sincroniza sus accesos y reemplaza estado y pendientes juntos. El coordinador
serializa la ejecución local y entrega pendientes antes de aceptar el evento
siguiente. No es un bloqueo distribuido ni una outbox durable.

No se modifican productor, otros microservicios, contratos compartidos, Compose
ni frontend. No se agregan REST, WebSockets, bases de datos, commits ni push.

## Configuración

El archivo application.properties contiene los valores predeterminados; Spring
permite sobrescribirlos mediante propiedades externas o variables de entorno.

| Propiedad | Variable / valor local |
|---|---|
| spring.kafka.bootstrap-servers | KAFKA_BOOTSTRAP_SERVERS / localhost:9092 |
| spring.kafka.consumer.group-id | match-state-service |
| match-state.kafka.topic | MATCH_EVENTS_TOPIC / match_events |
| spring.kafka.consumer.key-deserializer | StringDeserializer |
| spring.kafka.consumer.value-deserializer | StringDeserializer |
| spring.kafka.consumer.enable-auto-commit | false |
| spring.kafka.consumer.auto-offset-reset | earliest |
| spring.kafka.consumer.max-poll-records | 1 |
| spring.kafka.consumer.properties.max.poll.interval.ms | 300000 |
| spring.kafka.listener.ack-mode | record |
| spring.kafka.listener.auto-startup | KAFKA_LISTENER_AUTO_STARTUP / true |
| match-state.kafka.retry-interval | 1s |
| spring.rabbitmq.host | RABBITMQ_HOST / localhost |
| spring.rabbitmq.port | RABBITMQ_PORT / 5672 |
| spring.rabbitmq.username | RABBITMQ_USERNAME / guest |
| spring.rabbitmq.password | RABBITMQ_PASSWORD / guest |
| spring.rabbitmq.connection-timeout | 5s |
| spring.rabbitmq.publisher-confirm-type | correlated |
| spring.rabbitmq.publisher-returns | true |
| spring.rabbitmq.template.mandatory | true |
| spring.rabbitmq.template.retry.enabled | false |
| match-state.rabbit.exchange | RABBITMQ_EXCHANGE / live_updates |
| match-state.rabbit.routing-key | score.{matchId} |
| match-state.rabbit.confirm-timeout | 5s |

guest es únicamente el usuario de desarrollo local predeterminado de RabbitMQ;
no se incorpora ninguna credencial real. No se registran contraseñas, configuración
de conexión, cuerpos de mensajes ni mensajes de excepciones. Los logs propios
incluyen evento, partición, offset y tipo de fallo. Se desactiva el logging del
paquete de listeners de Spring Kafka para evitar que sus excepciones impriman
el ConsumerRecord completo; los logs propios de entrada y reintentos siguen activos.

La factory usa un consumidor, confirmaciones de offset síncronas y AckMode.RECORD.
El tiempo de espera de confirmación RabbitMQ es intencional: retornar antes
permitiría confirmar Kafka sin confirmar la operación crítica. El timeout empieza
después de send; no es un límite total de todas las operaciones de red del cliente.

El exchange se declara durable, tipo topic, sin auto-delete. No se crean colas ni
bindings de otros desarrolladores. Debe existir una cola enlazada con una clave
compatible, por ejemplo score.#. Sin binding, mandatory provoca retorno y se
considera fallo incluso si el exchange confirma la recepción.

### Registro previo de partidos

No se infieren los equipos a partir de un gol. Configurar antes de consumir:

```powershell
$env:MATCH_ID = 'match-001'
$env:HOME_TEAM_ID = 'home'
$env:AWAY_TEAM_ID = 'away'
```

Sin esos valores no se registra ningún partido. Un registro parcial o con ambos
equipos iguales impide iniciar por configuración inválida. Para varios partidos,
usar configuración Spring externa con match-state.matches[0].match-id,
home-team-id, away-team-id, y los índices siguientes. Los IDs son sensibles a
mayúsculas y deben coincidir exactamente con el productor. Los IDs usados en
routing keys también deben respetar los límites de claves de RabbitMQ.

## JSON y contratos provisionales

contracts/events/match-event.schema.json y score-update.schema.json están vacíos.
Se conservan intactos. La entrada toma como referencia la propuesta local del
productor. Acepta los cuatro EventType existentes, timestamp textual ISO 8601 y
minute entero obligatorio de 0 a 130. Conserva reference_event_id y admite la
omisión de los campos opcionales. Rechaza JSON inválido, claves duplicadas,
campos desconocidos, objetos concatenados, coerciones de números a IDs, enum
numérico, minuto fraccionario/nulo/ausente, tombstones y claves Kafka incorrectas.

La propuesta de salida es score-update.schema.proposed.json, PENDIENTE de
aprobación de DEV 2, DEV 3 y el equipo. La serialización real usa snake_case:

```json
{
  "match_id": "match-001",
  "home_score": 0,
  "away_score": 0,
  "event_id": "annulment-1",
  "event_type": "GOAL_ANNULLED",
  "updated_at": "2026-11-12T20:00:00Z",
  "var_status": "CLEAR",
  "reference_event_id": "goal-1"
}
```

event_id identifica el evento de entrada que originó la actualización, no un ID
nuevo en cada reintento. updated_at se fija al guardar el cambio y no cambia al
reenviar el pendiente. var_status representa el estado resultante, y
reference_event_id conserva la referencia original o null. Se publica una salida
por evento nuevo aceptado, incluso si solo cambia VAR o es MATCH_CONTINUES; los
duplicados exactos ya confirmados no generan otra salida. No se agregan cabeceras
de tipos Java. El cuerpo es JSON UTF-8, con entrega persistente y message_id igual
al event_id original. La persistencia efectiva requiere una cola durable y las
garantías apropiadas del broker; el publisher confirm no confirma consumo final.

## Idempotencia, fallos y offsets

1. MatchState mantiene las reglas de deduplicación y anulación de la Fase 5.
   Un gol no incrementa dos veces; una anulación no resta dos veces. El mismo ID
   con contenido distinto se rechaza. Los eventos inválidos no entran al historial.
2. Un evento nuevo y válido guarda estado y salida pendiente juntos en memoria.
3. El publicador espera confirmación correlacionada y comprueba los retornos.
   Fallos de serialización, conexión, nack, mensaje no enrutable, interrupción,
   futuro excepcional y timeout se propagan como fallos de publicación.
4. Solo después de confirmar RabbitMQ se elimina el pendiente. Si falla la
   publicación, el estado conserva el cambio y el pendiente conserva exactamente
   el mismo JSON lógico. El listener lanza la excepción y Kafka no confirma el offset.
5. Al reentregar el evento se publica el pendiente sin aplicar nuevamente el gol.
   Si RabbitMQ confirmó y el commit Kafka falló, el duplicado posterior no publica
   otra vez mientras el estado de este proceso siga disponible.
6. Un timeout o una caída entre la recepción Rabbit y la eliminación del pendiente
   pueden causar reenvío de un mensaje ya aceptado. Los consumidores Rabbit deben
   deduplicar por (match_id, event_id). No hay entrega exactamente una vez entre brokers.

DefaultErrorHandler reubica el registro fallido y reintenta con intervalo de 1s,
sin límite de intentos, sin recuperación que descarte mensajes y sin commit de
recuperados. Los fallos de deserialización se producen dentro del listener usando
StringDeserializer, por lo que siguen la misma política. Las pruebas verifican
seeks al mismo offset y ausencia de commits en el manejador de error.

Un error permanente puede detener el progreso de la partición y retrasar otros
partidos. Esta política prioriza no omitir una operación crítica. No se implementa
una DLT ni se avanza un offset manualmente. Antes de producción debe aprobarse un
procedimiento de cuarentena/reprocesamiento con retención del original. No basta
con descartar una excepción o desactivar auto-commit para garantizar consistencia.

La anulación adelantada conserva la política de la Fase 5: rechazo hasta que exista
el GOAL. Si ese GOAL está detrás en la misma partición, los reintentos no pueden
corregir el orden por sí solos: se necesita intervención y replay ordenado. No se
introduce lógica VAR o buffering de negocio en el listener para ocultar ese caso.

## Reinicios y límites operativos

El adaptador implementado es VOLÁTIL. Al reiniciar se pierden marcador, historial
de deduplicación y pendientes. Los partidos configurados vuelven a 0–0. Kafka
conserva offsets confirmados; auto-offset-reset=earliest solo se usa si no existe
un offset válido, y NO reconstruye automáticamente el estado.

Por tanto, reiniciar con offsets ya confirmados puede producir marcadores
incorrectos aunque los eventos nuevos sean válidos. Tampoco se debe escalar este
adaptador a varias instancias ni depender de una reasignación de particiones para
transferir estado. El historial crece en memoria sin política de retención.

La recuperación manual exige detener el procesamiento, disponer de todo el
historial Kafka necesario y reconstruir cada partido en orden. Un replay vuelve
a emitir actualizaciones, por lo que debe coordinarse la deduplicación y la posible
regresión de snapshots en consumidores. No se resetean offsets compartidos aquí.
Antes de producción se requiere aprobar almacenamiento durable para estado,
historial y outbox, junto con su recuperación, orden y estrategia de publicación.
No se instala ninguna base de datos sin autorización.

## Pruebas automatizadas y resultado

Desde C:/Live-JVG:

```powershell
mvn -f services/match-state-service/pom.xml clean verify
```

En el entorno del agente se utiliza la caché escribible del workspace:

```powershell
mvn -B -ntp -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -f services/match-state-service/pom.xml clean verify
```

La dependencia AMQP de Boot 3.5.7 no estaba en caché; la primera ejecución offline
falló por resolución de dependencias. Se descargó con acceso autorizado y sin
modificar ajustes globales. Una ejecución posterior detectó dos fallos: coerción
numérica a texto en Jackson y una expectativa incorrecta del tipo de excepción
interno de reintento de Spring Kafka. Se corrigieron ambos y se repitió la suite.

Verificación final del 2026-11-12: **BUILD SUCCESS**, 73 pruebas, 0 fallos,
0 errores y 0 omitidas; JAR ejecutable generado. El proceso Maven terminó con
código 0. Log completo: C:/Live-JVG/phase6-verify.log. Los escenarios originales
de la Fase 5 siguen pasando; las pruebas de contexto ahora incluyen dos checks
adicionales de configuración de brokers, sin conectarse a ellos.

La suite incluye todos los escenarios previos de la Fase 5, entrada de los cuatro
tipos de evento, validación JSON, duplicados, pendientes, reintentos sin doble
efecto, atomicidad local, límites de reinicio, independencia del dominio, offsets,
exchange, routing key, JSON de salida, confirms, nacks, retornos y timeouts. Una
prueba de componentes recorre listener → caso de uso → dominio → publicador real
con RabbitTemplate simulado. No es una prueba con brokers. La prueba de contexto
desactiva el arranque Kafka y las declaraciones Rabbit para evitar conexiones.

## Prueba real opcional

Comprobación del 2026-11-12: KAFKA_BOOTSTRAP_SERVERS y RABBITMQ_HOST no estaban
definidas. localhost:9092 no aceptó conexión; localhost:5672 sí tenía un puerto
abierto. Esto último no verifica autenticación AMQP. No se ejecutó la cadena real
porque Kafka no estaba disponible. No se inició infraestructura ni se cambió Compose.

Para ejecutar posteriormente en un entorno de desarrollo:

1. Disponer de Kafka y RabbitMQ accesibles, provisionar match_events y configurar
   las variables de conexión necesarias. No usar una partición con mensajes
   inválidos pendientes para este smoke test.
2. En RabbitMQ, usando las herramientas del equipo o la consola de administración,
   declarar live_updates como exchange topic durable; crear una cola de prueba
   durable y enlazarla a live_updates con score.phase6-smoke. Hacerlo antes de enviar.
3. Configurar MATCH_ID=phase6-smoke, HOME_TEAM_ID=home y AWAY_TEAM_ID=away. Iniciar
   el JAR de match-state-service. Para aislar el smoke test de offsets anteriores,
   usar un grupo de prueba propio y auto-offset-reset=latest:

```powershell
java -jar services/match-state-service/target/match-state-service-0.0.1-SNAPSHOT.jar --spring.kafka.consumer.group-id=phase6-smoke --spring.kafka.consumer.auto-offset-reset=latest
```

4. Esperar a que el consumidor de prueba tenga la partición asignada; comprobarlo
   con las herramientas Kafka del equipo. El grupo predeterminado de la aplicación
   sigue siendo match-state-service; el override anterior solo aísla esta prueba.
5. En otra terminal, construir el productor existente y obtener su classpath:

```powershell
mvn -f services/live-feed-producer/pom.xml package dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
$producerRoot = (Resolve-Path services/live-feed-producer).Path
$runtimeClasspath = (Get-Content "$producerRoot/target/runtime-classpath.txt" -Raw).Trim()
jshell --class-path "$producerRoot/target/classes;$runtimeClasspath"
```

6. Ejecutar en JShell para utilizar el adaptador real de live-feed-producer:

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
var goal = MatchEvent.create("phase6-smoke", EventType.GOAL, "home", 42, Instant.now(), null);
publisher.publish(goal).get(15, TimeUnit.SECONDS);
var annulment = MatchEvent.create("phase6-smoke", EventType.GOAL_ANNULLED, null, 43, Instant.now(), goal.eventId());
publisher.publish(annulment).get(15, TimeUnit.SECONDS);
publisher.publish(goal).get(15, TimeUnit.SECONDS);
publisher.publish(annulment).get(15, TimeUnit.SECONDS);
context.close();
```

7. Inspeccionar la cola de prueba: deben aparecer dos actualizaciones con exchange
   live_updates y routing key score.phase6-smoke: GOAL 1–0 y GOAL_ANNULLED 0–0,
   reference_event_id igual al ID del gol. Los duplicados finales no deben añadir
   mensajes. Verificar snake_case, timestamp y logs de confirmación Rabbit.
8. Para probar indisponibilidad, repetir con otro evento mientras se interrumpe
   el acceso del servicio a RabbitMQ en ese entorno aislado. Verificar error,
   offset sin avanzar y pendiente. Restaurar RabbitMQ sin reiniciar el servicio:
   el mensaje debe confirmarse y el marcador no debe volver a incrementarse.
9. Retirar únicamente la cola de prueba y detener sus procesos al finalizar.

Estos pasos quedan documentados para ejecución posterior; no se presentan como
resultados reales. Un timeout en JShell no demuestra que el evento no llegara a Kafka.

## Pendientes con DEV 2 y DEV 3 antes de Fase 7

- Aprobar ambos contratos, nombres de campos, nulls, enums y semántica VAR.
- Confirmar la fuente de equipos y registro de partidos; el evento de entrada
  no contiene los dos equipos necesarios para crear el estado inicial.
- Acordar colas, bindings, durabilidad, permisos, deduplicación y manejo de
  snapshots repetidos o fuera de orden por los consumidores Rabbit.
- Aprobar persistencia y recuperación; el adaptador en memoria es solo local.
- Acordar gestión de mensajes inválidos/anulaciones adelantadas, retención Kafka,
  disponibilidad, métricas, timeouts de red y procedimiento de replay.
- Ejecutar el smoke test real antes de aceptar la comunicación entre servicios.

## Archivos de esta fase

Modificados:

- pom.xml
- src/main/resources/application.properties
- src/main/java/com/live_jgv/matchstate/application/service/MatchStateService.java
- src/main/java/com/live_jgv/matchstate/domain/port/MatchStateRepository.java
- src/test/java/com/live_jgv/matchstate/MatchStateServiceApplicationTests.java
- src/test/java/com/live_jgv/matchstate/application/service/MatchStateServiceTest.java

Creados, bajo src/main/java/com/live_jgv/matchstate:

- domain/model/ScoreUpdate.java
- domain/port/ScoreUpdatePublisher.java
- domain/port/ScoreUpdateOutbox.java
- application/service/ProcessAndPublishMatchEvent.java
- entrypoint/kafka/MatchEventMessage.java
- entrypoint/kafka/InvalidKafkaMessageException.java
- entrypoint/kafka/KafkaMatchEventListener.java
- infrastructure/config/MatchCatalogProperties.java
- infrastructure/config/MessagingConfiguration.java
- infrastructure/memory/InMemoryMatchStateStore.java
- infrastructure/rabbit/RabbitScoreUpdatePublisher.java
- infrastructure/rabbit/ScoreUpdatePublicationException.java

Creados, bajo src/test/java/com/live_jgv/matchstate:

- application/service/ProcessAndPublishMatchEventTest.java
- entrypoint/kafka/KafkaMatchEventListenerTest.java
- entrypoint/kafka/MessagingPipelineTest.java
- infrastructure/config/KafkaRetryPolicyTest.java
- infrastructure/memory/InMemoryMatchStateStoreTest.java
- infrastructure/rabbit/RabbitScoreUpdatePublisherTest.java

Documentos creados: docs/phase-6.md y docs/score-update.schema.proposed.json.
Los logs de ejecución phase6-*.log son artefactos locales ignorados por Git.
