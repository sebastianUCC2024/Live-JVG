# FASE 7I — Cuarentena durable y replay controlado

Fecha: 2026-11-14, America/Bogota. Resultado: **PASS del alcance implementado**.
Rama conservada: feature/Valeria-infra-history. Trabajo y verificaciones ejecutados
por Codex, sin comandos manuales solicitados al usuario.

## Preparación y alcance

Se revisaron la [auditoría 7F](phase-7f-robustness-report.md), el
[diseño 7G](phase-7g-reliability-design.md), la
[implementación 7H](phase-7h-persistence-implementation-report.md), el estado Git
y las fuentes reales. Se conservaron todos los cambios pendientes de 7H y docs/
sin seguimiento; no se usaron reset, checkout, merge ni cambios de rama.

Se implementan custodia durable de entradas problemáticas, identidad de eventos
diferidos, replay explícito y ciclos de reintento limitados. No se añaden endpoints,
tecnologías, dependencias, contratos v2 ni modificaciones downstream. El POM sigue
con las dependencias ya incorporadas en 7H; sus cambios pendientes en Git son de
esa fase, no nuevas dependencias de 7I.

## Arquitectura y transacciones

El listener Kafka de producción recibe bytes mediante ByteArrayDeserializer;
el JSON y la clave enviados por el productor no cambian. La decodificación UTF-8
es estricta y sucede después de capturar el original, incluidos tombstones y
bytes que no sean UTF-8. Se guardan key/value como BYTEA, timestamp del registro
y headers ordenados con valores Base64, distinguiendo null y encabezados repetidos.

`KafkaInputSource` obtiene cluster ID y UUID real del topic con AdminClient antes
de registrar la ocurrencia. La clave de custodia es source_id + topic + partición
+ offset. El source_id combina namespace configurado y esos identificadores:
recrear el topic no reutiliza los recibos de la encarnación anterior. Si no se
puede establecer esa identidad, el registro NO se confirma. Requiere permisos
Kafka de descripción y soporte de topic UUID, disponibles en Kafka 3.9.1 probado.

`DurableEventIngress.handle(InputRecord)` es la entrada durable. En una transacción
PostgreSQL reserva/verifica la identidad, clasifica el evento y guarda:

- Evento válido: historial, revisión CAS, outbox de 7H, identidad APPLIED y recibo.
- Rechazo permanente: caso de cuarentena y recibo, sin modificar el marcador.
- Referencia pendiente/partido sin catálogo: caso DEFERRED e identidad reservada,
  sin registrar el evento como aplicado.

Solo tras ese commit puede retornar al listener un rechazo/deferencia. Para un
evento aplicado drena primero la outbox mediante el mismo publicador 7H; si falla
RabbitMQ o marcar publicado, el error continúa impidiendo el commit Kafka.
Los fallos de PostgreSQL no son atrapados como JSON inválido.

La reentrega del mismo registro verifica sus bytes y reutiliza el recibo. Si las
coordenadas ya registradas reaparecen con otros bytes, se falla de forma visible,
sin asumir que sean un duplicado. Para eventos bien formados, la identidad es
(match_id,event_id) y se compara el record completo normalizado, no solo su hash.
Dos ocurrencias Kafka del mismo diferido/rechazado comparten caso pero conservan
sus recibos separados. Un conflicto de contenido genera su propio caso sin
sobrescribir la identidad original.

Los registros 7H se cargan desde el ledger igual que antes. La migración y la
lectura de entradas permiten reconocer identidades APPLIED antiguas; un evento
histórico no vuelve a aplicarse por carecer de un recibo nuevo.

## Migraciones y estructura durable

V1 permanece intacta. Flyway añade:

| Migración | Contenido |
|---|---|
| V2__quarantine_and_replay.sql | event_identity, quarantine_case, input_receipt, replay_attempt; backfill APPLIED desde accepted_event; índices/FK |
| V3__custody_integrity.sql | Constraints que exigen caso para recibos aparcados e identidades DEFERRED/REJECTED |

V3 se mantuvo incremental porque V2 ya se había aplicado a las bases aisladas
durante la validación. No se alteró su checksum para evitar errores de Flyway.
No se borraron esquemas o bases para repetir pruebas.

| Tabla | Datos / función |
|---|---|
| input_receipt | Coordenadas, bytes originales, headers, identidad extraíble, disposición original, case_id, contador de entregas comprometidas y fechas |
| quarantine_case | ID estable, motivo, QUARANTINED/DEFERRED/RESOLVED, identidad/referencia y evento canónico cuando es válido estructuralmente |
| event_identity | Identidad de negocio reservada, payload canónico, NEW/APPLIED/DEFERRED/REJECTED, caso asociado |
| replay_attempt | replay_id único, caso, actor, motivo, revisión aprobada, resultado, detalle y fecha |

Si el JSON es ilegible, puede no existir una identidad fiable. Si es parseable,
se extraen event_id/match_id/reference_event_id textuales disponibles, aun con
enum o timestamp inválido, sin coerción de números a identificadores. Los bytes
originales son siempre la fuente de diagnóstico, no esa extracción parcial.

El outcome original de un recibo DEFERRED permanece como registro histórico
después de resolverlo: la resolución se consulta en quarantine_case y
event_identity. No se reescribe la historia como si se hubiera aplicado al recibirlo.

## Clasificación implementada

| Clase / reason_code | Ejemplos | Comportamiento |
|---|---|---|
| MALFORMED | JSON roto, campos ausentes, enum desconocido, tipos incorrectos, fecha inválida, UTF-8 inválido, tombstone, clave incorrecta | Cuarentena durable inmediata; sin actualización RabbitMQ |
| UNKNOWN_TEAM / DOMAIN_INVALID | Rechazos semánticos del agregado | Cuarentena sin cambiar estado |
| ID_CONFLICT | Mismo ID y contenido diferente | Preservar original, registrar conflicto |
| REFERENCE_NOT_GOAL | Referencia conocida de tipo distinto a GOAL | Rechazo permanente |
| GOAL_ALREADY_ANNULLED | Otra anulación diferente sobre el gol ya anulado | Rechazo permanente; sin segunda resta |
| WAITING_REFERENCE | Anulación o VAR_CHECK referido cuyo gol no existe aún | DEFERRED; permite llegar a registros posteriores |
| UNREGISTERED_MATCH | No existe catálogo del partido | DEFERRED para revisión; no inferir equipos |
| Fallo de BD / CAS / publicación / identidad de origen | Conexión, conflicto, timeout, nack/return, metadatos Kafka inaccesibles | Propagar; no convertir en evento inválido ni confirmar silenciosamente |
| Bug o corrupción no clasificados | Historia/identidad incoherente | Propagar y pausar por presupuesto; no descartar |

Las razones semánticas son tipadas en InvalidMatchEventException.Reason.
No se comparan textos de excepciones para decidir si una referencia es adelantada.
No se clasifica todo IllegalArgumentException producido por cualquier capa como
permanente: el catch de formato está limitado a la decodificación de la entrada.

## Reintentos y continuidad

`BoundedRetryErrorHandler` conserva AckMode.RECORD, ackAfterHandle=false y
commitRecovered=false. Nunca utiliza un recuperador log-only que omita el registro.

Configuración por defecto: intento inicial y tres reintentos con esperas 1, 2 y
4 segundos. Al agotar el presupuesto pausa el contenedor durante 30 segundos y
después permite un nuevo ciclo. La pausa usa la API del contenedor; no hay seeks
ni commits desde el hilo del temporizador. Se mantienen polls/heartbeats del
contenedor pausado y el offset pendiente.

El límite es **por ciclo**, no un descarte después de cuatro fallos ni un límite
durable de intentos de toda la vida del evento. Un error prolongado sigue retenido,
con ciclos espaciados y logs de presupuesto/cooldown. No existe un health probe
externo: al terminar cooldown se vuelve a intentar el registro, que comprueba las
dependencias reales. Un reinicio reinicia ese presupuesto operativo, sin perder
los recibos/casos ya comprometidos.

Se observan topic/partición/offset, intento y clase del fallo en logs; los cuerpos
y los motivos libres del operador no se imprimen como parte de esos logs.
delivery_attempts cuenta entregas registradas exitosamente, no intentos durante
una caída de BD en la que es imposible guardar auditoría. Esos fallos aparecen
en logs, no se presentan como intentos durables guardados.

## Replay explícito: procedimiento y garantías

La operación administrativa es el bean `DurableEventIngress`:

1. Consultar `cases()` y los recibos enlazados para revisar bytes, motivo,
   referencia y estado. Identificar el caso concreto; no ejecutar un barrido de replay.
2. Verificar catálogo, gol referenciado, estado VAR y revisión actuales.
3. Crear `ReplayRequest(replayId, caseId, actor, reason, expectedRevision)`.
4. Invocar `replay(request)` desde el contexto autorizado existente. El test real
   ejecutó esta misma API; no se implementó un endpoint HTTP ni una CLI remota.
5. Consultar resultado, replay_attempt y outbox. APPLIED describe aplicación en
   BD, no una garantía exactly-once ni confirmación de consumo downstream.

Ejemplo de API ilustrativo, no una nueva ejecución ni petición de comandos:

```java
var ingress = context.getBean(DurableEventIngress.class);
var approval = new ReplayRequest(replayId, caseId, actor, reason, reviewedRevision);
var result = ingress.replay(approval);
```

| Resultado | Significado |
|---|---|
| APPLIED | Evento original aceptado, caso resuelto y auditoría comprometida con ledger/outbox |
| ALREADY_APPLIED | Otra solicitud sobre un caso ya aplicado; sin otro efecto |
| REVIEW_REQUIRED | Catálogo/revisión no coincide con lo aprobado; solicitar nueva revisión |
| REJECTED | No es replayable o incumple todavía las reglas, por ejemplo referencia ausente o gol ya anulado |

Se conserva event_id, timestamp y referencia originales. updated_at corresponde
a la aceptación efectiva y queda fijado en outbox. El replay usa processAtRevision
y CAS, por lo que una revisión cambiada durante la aceptación no aplica la
anulación bajo una aprobación antigua. No hay reordenamiento histórico automático.

El mismo replay_id con idéntica solicitud devuelve el resultado registrado; el
mismo ID con actor/caso/motivo/revisión distintos se rechaza como reutilización.
Un rechazo o REVIEW_REQUIRED necesita una nueva solicitud/ID después de revisar,
no cambiar los datos de una solicitud ya registrada.

Una publicación fallida después del commit deja caso/identidad aplicados,
replay_attempt y outbox pendiente. Repetir la solicitud original drena el pendiente
sin aplicar otra vez el evento. El arranque de 7H también puede recuperarlo.
Un fallo SQL antes del commit revierte aplicación y auditoría juntos; no se
anuncia éxito ni se exige inventar una auditoría mientras PostgreSQL está caído.

Al llegar el gol, no se invoca replay automáticamente: el caso diferido permanece
para revisión. Reaplicar una anulación tarde puede cerrar una revisión VAR más
reciente; se exige evaluación explícita del estado actual. JSON roto o rechazo
permanente no se corrigen editando el original; la política operativa es enviar
la corrección como una nueva entrada/ID y conservar su trazabilidad fuera del payload v1.
Una identidad extraída parcialmente de JSON malformado sirve para búsqueda,
no reserva una identidad canónica de negocio: ese JSON no representa todavía
un MatchEvent válido. La política de IDs nuevos para esas correcciones es operativa,
no un rechazo automático de cualquier entrada futura con ese texto de ID.

La API está destinada a código administrativo confiable con acceso al contexto y
la BD; actor es un dato de auditoría, no autenticación criptográfica. No abrir una
segunda instancia contra el mismo almacén para usarla: sigue vigente el límite de
una instancia de publicación de 7H. CLI, UI, RBAC y automatización de aprobaciones
no se implementaron ni se afirman disponibles.

## Pruebas ejecutadas

Java 17.0.20.1, Maven 3.10.0, PostgreSQL 17 y los brokers reales existentes.
PostgreSQL aislado nuevo: `live-jvg-7h-7i-postgres`, puerto local 55433, volumen
`live-jvg-7i-pgdata`. Su prefijo permite reutilizar la guarda de seguridad del
test 7H sin detener el PostgreSQL histórico de esa fase.

Bases nuevas: phase7i_tests, phase7i_brokers y phase7i_quarantine. Pruebas de
migración usan esquemas nuevos upgrade_<UUID>. No se eliminaron bases/esquemas
históricos. Cada ejecución completa usa topics, colas, grupos y partidos nuevos.

| Verificación final | Pruebas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|
| match-state-service clean verify | 102 | 0 | 0 | 0 |
| live-feed-producer clean verify, sin cambios | 43 | 0 | 0 | 0 |

Ambos BUILD SUCCESS, código 0. Total: **145 pruebas ejecutadas**. Incluyen la
regresión completa de los escenarios 7H y las pruebas nuevas SQL, de reintentos
y de mensajería de cuarentena/replay. Las pruebas opt-in fueron habilitadas en
la ejecución final; las ejecuciones previas sin brokers se registran con sus
omisiones y no sustituyen este resultado.

Después de esa ejecución completa se reforzó la aserción SQL de rollback para
comprobar directamente que tampoco queda un quarantine_case huérfano. Se repitió
solo DurableIngressIntegrationTest: 13 pruebas, 0 fallos/errores/omisiones. El
log y XML de esa repetición están separados; no se suman otra vez a los 145 casos
únicos de la regresión completa. No hubo cambios de producción después de ella.

| Prueba requerida / adicional | Evidencia | Resultado |
|---|---|---|
| JSON malformado, campos ausentes y enum desconocido | SQL y Kafka reales; recibos con originales | PASS |
| Timestamp inválido, UTF-8 inválido y tombstone | Fixtures PostgreSQL; fecha no entra en retries transitorios | PASS |
| Evento de dominio inválido | UNKNOWN_TEAM, cero cambios/salidas falsas | PASS |
| Válido después de inválido | Kafka avanza y gol posterior produce snapshot correcto | PASS |
| Anulación adelantada y llegada del gol | DEFERRED antes; GOAL posterior llega al agregado; no autoreplay | PASS |
| Replay exitoso | Original aplicado con referencia, nueva revisión y outbox | PASS |
| Replay repetido | Misma solicitud y solicitud nueva sobre caso aplicado: sin segunda resta/salida | PASS |
| Replay rechazado | JSON no replayable, referencia aún ausente o gol ya anulado | PASS |
| Aprobación obsoleta | REVIEW_REQUIRED, sin aplicación | PASS |
| Duplicado en cuarentena | Mismo diferido/rechazado comparte caso; misma ocurrencia conserva un recibo | PASS |
| Conflicto con identidad reservada | Otro payload no sobrescribe original ni cambia referencia | PASS |
| Caída PostgreSQL durante cuarentena | Offset congelado y pausa observada | PASS |
| Recuperación PostgreSQL | Custodia guardada antes del commit siguiente; válido posterior aplicado | PASS |
| Fallo SQL a mitad de custodia | Trigger solo del fixture: rollback de caso/recibo; retry crea uno | PASS |
| Reinicio con revisión pendiente | Casos/identidad originales siguen presentes, cero falsas salidas | PASS |
| Publicación fallida después de replay | Auditoría/APPLIED conservados; reintento drena sin reaplicar | PASS |
| Actualizar V1 poblada | Mantiene ledger, revisión y wire image; backfill APPLIED | PASS |
| Gate, outbox, reinicios, caída PG, dedup de 7H | Se repite su prueba real y suites | PASS |
| Compatibilidad Kafka/RabbitMQ | Bytes UTF-8 de entrada y snapshots de los mismos campos v1 | PASS para lo observado |

## Evidencia real de continuidad e idempotencia

Prefijo final: `phase7i-c7e3cf01-e98e-4677-8c71-a45ae4df3ac5`.
Sufijos -events, -group, -queue, -updates y -match. Caso diferido final:
`21328e45-d8e9-4db4-9175-6fa2bd36c1d6`.

| Offset de entrada | Disposición |
|---:|---|
| 0 | JSON roto, cuarentena |
| 1 | Campos ausentes, cuarentena |
| 2 | Enum desconocido, cuarentena |
| 3 | Equipo ajeno, cuarentena |
| 4 | Anulación adelantada, diferida |
| 5 | Mismo evento diferido, mismo caso |
| 6 | GOAL referenciado, aplicado; después replay revisado de anulación |
| 7 | Reentrega del evento de anulación ya aplicado, sin nuevo efecto |
| 8 | GOAL visitante válido, aplicado |
| 9 | JSON inválido durante caída PG; retenido, después cuarentenado |
| 10 | GOAL local posterior a recuperación, aplicado |

Resultado observado: offset confirmado 11, once recibos, seis casos únicos,
revisión 4, marcador final 1–1 y cuatro mensajes RabbitMQ con IDs distintos.
La aplicación del replay no es un nuevo registro Kafka: no confundir revisión
del agregado con offset ni contar dos veces esa anulación.

La caída de PG agotó cuatro intentos y activó la pausa de 2000 ms configurada
solo para la prueba. El offset se mantuvo en 9 y la cola en tres mensajes.
Al restaurar el contenedor nuevo, se guardó cuarentena y el offset pasó a 10;
el siguiente gol llevó a 11 y a cuatro salidas. Los errores de conexión/rollback
que aparecen en el log son parte de esa inyección, no éxitos omitidos.

El replay final registra REVIEW_REQUIRED, APPLIED, ALREADY_APPLIED y REJECTED en
cuatro solicitudes distintas. Repetir el replay_id APPLIED no crea otro intento
ni otra salida. Las pruebas SQL incluyen también rechazo por doble anulación y
reintento de publicación posterior al commit.

Los cuatro payloads RabbitMQ corresponden a GOAL local 1–0, anulación 0–0,
GOAL visitante 0–1 y GOAL local posterior 1–1. La inspección de la cola propia
usa lecturas sin autoAck y reencolado; se conservan esos mensajes.

## Comandos, configuración y reproducción

Comandos de verificación ejecutados con JAVA_HOME del JDK 17:

```sh
/opt/homebrew/bin/mvn -o -f services/match-state-service/pom.xml clean verify
/opt/homebrew/bin/mvn -o -f services/live-feed-producer/pom.xml clean verify
```

Variables habilitadas en la ejecución final:

```text
MATCH_STATE_TEST_DB_URL=jdbc:postgresql://localhost:55433/phase7i_tests
MATCH_STATE_BROKER_DB_URL=jdbc:postgresql://localhost:55433/phase7i_brokers
MATCH_STATE_QUARANTINE_DB_URL=jdbc:postgresql://localhost:55433/phase7i_quarantine
MATCH_STATE_TEST_DB_USERNAME=match_state
MATCH_STATE_TEST_DB_PASSWORD=<contraseña desechable del PostgreSQL nuevo>
MATCH_STATE_TEST_BROKERS=true
MATCH_STATE_TEST_QUARANTINE=true
MATCH_STATE_TEST_POSTGRES_CONTAINER=live-jvg-7h-7i-postgres
```

Las pruebas de caída detienen/restauran exclusivamente ese contenedor designado.
No apuntar estas variables a infraestructura compartida. Se mantienen las variables
de conexión de 7H; no se agregan credenciales reales al repositorio.

Configuración nueva: `match-state.kafka.retry-cooldown` (30 s), source namespace
`match-state.kafka.source-id` / KAFKA_SOURCE_ID (local/1 por defecto). El namespace
debe permanecer estable para el mismo servicio/origen; cluster y topic UUID se
resuelven en cada entrada. El perfil memory conserva la ruta de fixtures sin
garantías de cuarentena durable y no debe usarse para validar estas funcionalidades.

Otras inspecciones ejecutadas: git status/branch/diff, lectura de fuentes e informes,
verificación de hashes, consultas SQL de recibos/casos/replay/ledger, estado de colas
y descripción del grupo 7E. No se publicaron mensajes en match_events ni se
consumió la cola de evidencia 7E.

## Archivos modificados en esta fase

Todo el código está bajo services/match-state-service; rutas abreviadas relativas
a src/main/java/com/live_jgv/matchstate salvo indicación distinta:

- Nuevos: domain/model/InputRecord.java, ReplayRequest.java;
  domain/port/IncomingEventHandler.java;
  infrastructure/jdbc/DurableEventIngress.java;
  infrastructure/config/BoundedRetryErrorHandler.java, KafkaInputSource.java.
- Adaptados: domain/model/InvalidMatchEventException.java, MatchState.java;
  application/service/MatchStateService.java (aceptación ligada a revisión);
  entrypoint/kafka/KafkaMatchEventListener.java;
  infrastructure/config/MessagingConfiguration.java, DurableStorageConfiguration.java.
- Recursos: application.properties y migraciones V2/V3 de src/main/resources/db/migration.
- Tests nuevos: infrastructure/jdbc/DurableIngressIntegrationTest.java,
  QuarantineMessagingIntegrationTest.java, QuarantineMigrationIntegrationTest.java.
- Tests adaptados: MatchStateServiceApplicationTests.java y
  infrastructure/config/KafkaRetryPolicyTest.java.
- Documentación nueva: este informe y docs/evidence/phase-7i.

Las demás modificaciones que Git muestra del bloque 7H se conservaron; no se
atribuyen a esta fase. No se cambió V1, el POM ni el código del productor.

## Incidencias investigadas y límites

Durante compilación se detectó que buildAdminProperties de Boot 3.5.7 necesita
SslBundles; se corrigió usando el proveedor de bundles de Boot y se repitieron las
pruebas. En revisión se identificó que Instant.parse lanza DateTimeException,
que debe clasificarse como formato inválido; se añadió el catch específico y un
test de regresión de fecha inválida/identidad extraíble. No se afirmó un fallo
real previo de Kafka por ese caso: se detectó por inspección y luego se verificó.

Límites pendientes:

- Actor/motivo son auditoría de un operador confiable, no RBAC ni firma digital.
  Requests sin campos requeridos, caso inexistente o replay_id reutilizado con
  otros parámetros se rechazan; no se crea un caso ficticio para auditarlos.
- Sigue el límite de una instancia de publicación de 7H. No se ensayó liderazgo,
  failover distribuido ni concurrencia entre administradores en procesos distintos.
- Pausa global del contenedor y presupuesto por ciclo; un bug o infraestructura
  caída permanece retenido y puede requerir intervención, sin convertirse en pérdida.
- No hay alertas externas de antigüedad/SLA, cuotas ni purga automática de casos.
  La BD debe tener capacidad, backup y controles de acceso para originales/headers.
- La resolución de referencias no es automática. No se corrigen/reordenan eventos
  deportivos por timestamp ni se garantiza la semántica de una revisión VAR antigua.
- Dos consultas administrativas Kafka por entrada añaden coste; medirlo antes de
  optimizar cache sin perder detección de nuevas encarnaciones de topics.
- Persisten las ventanas de confirmación incierta de RabbitMQ: no se implementó
  inbox downstream, contratos v2 ni garantía exactamente una vez.
- No se ensayaron crash de host ni restauración de backup atrasado respecto a offsets.
  Los reinicios probados son contextos Spring completos y PostgreSQL aislado.

Siguiente fase recomendada: revisión operativa de aprobaciones/RBAC y métricas de
cuarentena; después, coordinación de inbox durable y revisiones de snapshots con
DEV 2/3. No se implementó ese alcance sin autorización.

## Evidencias y conservación

[evidence/phase-7i](evidence/phase-7i/) contiene logs completos, resumen de
checkpoints/payloads, reportes Surefire XML y consultas SQL de cuarentena,
recibos, replay y bytes originales; hashes SHA-256 para integridad. Los logs de
la compilación inicial fallida y las ejecuciones con pruebas omitidas se distinguen
de la verificación final con cero omisiones.

Los 33 hashes de evidencias 7F y 28 de 7H siguen coincidiendo. La inspección final
mantiene cola 7E con cinco mensajes y grupo con offset 5/lag 0. No se reiniciaron
sus brokers, PostgreSQL histórico o servicios. Los recursos nuevos 7I se conservan
para revisión; solo se retiraron los triggers de fallo creados por sus propios tests.

Sin commits, merges, pushes, cambio de rama ni modificaciones de contratos públicos.
