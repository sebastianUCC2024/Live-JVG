# Fase 5: estado del partido y VAR

## Alcance y arquitectura

Solo se modifica `services/match-state-service`. Se conserva el arranque Spring y
su prueba de contexto existentes. No se modifican productor, contratos, servicios
ajenos, frontend ni Compose. No se agregan endpoints, brokers ni base de datos.

El POM inicial usaba Boot 4.1.2 con starters de mensajería y pruebas de Boot 4.
Se alinea a Boot 3.5.7, Java 17 y JUnit 5, igual que live-feed-producer. Se sustituyen
los starters del esqueleto sin uso por starter y starter-test. No había lógica de
negocio que sobrescribir. El servicio de aplicación se construye con `new` e
inyección por constructor, sin anotaciones Spring ni necesidad de arrancar un contexto.
No se registra como bean mientras no exista un adaptador del puerto de almacenamiento.

```text
src/main/java/com/live_jgv/matchstate/
  domain/model/
    MatchEvent.java
    EventType.java
    MatchState.java
    Score.java
    VarStatus.java
    InvalidMatchEventException.java
  domain/port/
    ProcessMatchEvent.java
    MatchStateRepository.java
  application/service/
    MatchStateService.java
  infrastructure/package-info.java
  entrypoint/package-info.java
src/test/java/com/live_jgv/matchstate/
  domain/model/MatchStateTest.java
  domain/model/MatchEventTest.java
  domain/model/DomainIndependenceTest.java
  application/service/MatchStateServiceTest.java
  MatchStateServiceApplicationTests.java (existente, sin cambios)
```

`MatchState` es un agregado inmutable con constructor privado y fábrica `start`.
Los IDs de partido y equipos son strings no vacíos, con igualdad exacta y sensible
a mayúsculas; no se normalizan. Los equipos local y visitante deben ser distintos.
`MatchEvent` y `Score` son records. El modelo de eventos es local al microservicio:
no hay dependencia compilada del productor ni un módulo compartido improvisado.
`EventType` y las validaciones iniciales de MatchEvent corresponden al modelo actual
del productor. Esta duplicación de frontera permite despliegues independientes;
no se repiten reglas del agregado en la capa de aplicación.

## Reglas de estado

- GOAL registra un gol identificado por eventId, asociado a uno de los dos equipos.
- El marcador se deriva de los goles aceptados que no figuran en annulledGoalIds.
  No existe un decremento público ni una asignación directa del marcador.
- GOAL_ANNULLED exige referenceEventId de un GOAL previamente aceptado en ese
  partido. Rechaza referencias inexistentes, a eventos que no son goles, a goles
  de otros partidos y a goles ya anulados. Cada anulación revierte exactamente
  un gol. Si trae teamId, debe coincidir con el equipo del gol original.
- Cualquier teamId presente debe pertenecer al partido, incluso en eventos VAR.
  Los campos opcionales pueden ser null; una cadena vacía no equivale a null.
- VAR_CHECK no cambia el marcador y establece IN_PROGRESS. Sin referencia se
  considera revisión general; con referencia exige un gol activo de ese partido.
- MATCH_CONTINUES no cambia el marcador y establece CLEAR. Su referenceEventId,
  si existe, se conserva como metadato sin validación semántica en esta fase.
- Una anulación válida también establece CLEAR. No se exige revisión previa para
  anular. Se representa una única revisión general por partido; no revisiones
  simultáneas por gol. Un GOAL no cierra una revisión en curso.
- Se conservan los eventos aceptados completos, los goles registrados y sus IDs
  anulados. Las colecciones expuestas son inmutables; los mapas no prometen orden
  de iteración. Los snapshots previos permanecen intactos.
- Un duplicado idéntico retorna el mismo snapshot, sin escritura ni cambio de
  marcador/VAR. Igual ID con diferente contenido se rechaza. La deduplicación es
  por partido; la unicidad global de IDs continúa siendo una suposición del contrato.
- Los eventos de otro partido se rechazan antes de consultar referencias.
- Los rechazos semánticos usan InvalidMatchEventException; estructura inválida,
  partido no registrado o entrada nula producen IllegalArgumentException.
  Ningún rechazo altera el snapshot ni entra en el historial de eventos aceptados.
  El registro durable de rechazos corresponde a un futuro adaptador.

## Orden y concurrencia

Se aplica el orden de invocación de eventos aceptados; no se reordena por minute ni
timestamp y no se rechazan timestamps antiguos. Por ello, un MATCH_CONTINUES tardío
puede cerrar la revisión actual. El equipo debe aprobar esta semántica provisional.

Una anulación anterior a su gol se rechaza sin almacenar pendientes ni marcar su
ID como procesado. Después de recibir el GOAL puede reenviarse exactamente la
misma anulación y procesarse. El servicio no reintenta automáticamente ni recupera
por sí mismo eventos rechazados. La integración deberá definir retención, reintentos
limitados y tratamiento de errores; descartar esa anulación perdería su efecto.

El puerto de entrada ProcessMatchEvent recibe el evento. MatchStateService busca
el partido mediante MatchStateRepository, delega todas las reglas al agregado y
solicita compareAndSet para persistir estado e historial de forma atómica. Un
partido debe registrarse previamente con ambos equipos; los eventos deportivos no
permiten inferir quién es local y visitante. No se implementa registro/persistencia
ni se instala un repositorio en memoria en producción.

compareAndSet debe fallar si el snapshot leído ya cambió. El servicio propaga
ConcurrentModificationException para que el llamador recargue y reintente. Un
adaptador durable necesitará revisión explícita y persistencia atómica, no igualdad
de referencia Java. Los fallos de almacenamiento se propagan sin modificar el
snapshot cargado. No se promete exactly-once ni atomicidad con un broker futuro.
No existe todavía un puerto de publicación porque esta fase no publica resultados;
se diseñará junto con el contrato de salida y las garantías de entrega.

El agregado copia su historial al aceptar eventos y calcula score recorriendo los
goles; es adecuado para el alcance actual. Antes de usarlo para historiales grandes,
definir retención y persistencia sin perder la información de deduplicación.

## Contratos y pendientes con DEV 2 / DEV 3

Todos los schemas compartidos inspeccionados están vacíos. La referencia es
`../live-feed-producer/docs/match-event.schema.proposed.json`, PENDIENTE de aprobación.
No se modifica ningún contrato. No hay serialización ni deserialización JSON en
esta fase. Los futuros adaptadores deben mapear snake_case a este modelo.

La propuesta contiene event_id, match_id, event_type, team_id, minute, timestamp y
reference_event_id. Mantiene límite provisional de minuto 0..130, timestamp Instant
y tipos GOAL, VAR_CHECK, GOAL_ANNULLED, MATCH_CONTINUES. Las validaciones de equipo,
referencia, duplicado y pertenencia al partido son reglas del consumidor, no del
JSON Schema. El modelo acepta IDs no vacíos, sin imponer formato UUID.

Acordar con DEV 2 y DEV 3 antes de integrar:

1. Contrato compartido definitivo y compatibilidad de nombres, nulls y timestamps.
2. Fuente autoritativa de matchId, homeTeamId y awayTeamId y registro previo.
   El evento actual carece de información para crear un partido completo.
3. Semántica de referencias opcionales, identidad del equipo en anulaciones y
   una única revisión VAR por partido; estas restricciones son adicionales al schema.
4. Orden por partido, reintentos de anulaciones adelantadas, conflictos de IDs y
   gestión de rechazos. El contrato no incorpora número de secuencia.
5. Contrato de score-update, actualmente vacío, y responsabilidades de los demás
   consumidores. No se asumen implementaciones ni garantías de DEV 2 o DEV 3.
6. Estrategia de persistencia, revisiones y entrega de resultados antes de agregar
   adaptadores; ninguna base de datos se elige sin aprobación.

## Verificación real

Se escribieron primero las pruebas del agregado; la primera ejecución falló en
compilación por ausencia de las clases de dominio (paso rojo). Tras implementarlas
se añadieron pruebas del puerto de almacenamiento con Mockito y de arquitectura.

Comando ejecutado desde C:/Live-JVG:

```powershell
mvn -B -ntp -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -f services/match-state-service/pom.xml clean verify
```

Resultado del 2026-11-12: BUILD SUCCESS, 27 pruebas, 0 fallos, 0 errores y 0 omitidas.
Incluye 12 casos del agregado, 5 validaciones del evento, 8 casos de aplicación,
1 prueba de independencia y 1 prueba de contexto existente. Las 26 pruebas de
negocio/arquitectura no arrancan Spring. Mockito simula el repositorio; no se necesita
Docker, Kafka, RabbitMQ ni base de datos. Se generó el JAR ejecutable.

Se usó la caché Maven local del workspace ya disponible, en modo offline. En este
entorno el repositorio predeterminado había resuelto a C:/.m2 sin permiso de escritura;
el parámetro local evita esa ubicación. No se modificaron ajustes Maven globales ni
se descargaron dependencias para esta fase. Log completo en C:/Live-JVG/phase5-verify.log.

Antes de Fase 6, revisar los acuerdos anteriores, las excepciones que deberá gestionar
el adaptador, el requisito de compareAndSet y cómo conservar el historial. La Fase 6
queda pendiente de aprobación. No se hicieron commits ni push.
