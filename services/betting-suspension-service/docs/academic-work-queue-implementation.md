# Work Queue académica V1 — implementación offline

## Estado y alcance

LISTO PARA REVISIÓN del código offline; **no autorizado ni certificado para ejecución externa**.
Base `257662cc6fb5c300e01e5ad4ad967566dd60af88`, rama
`feature/valeria-academic-work-queue`, worktree `/private/tmp/live-jvg-dev3-academic-work-queue`.
Solo cambian Betting Suspension y Betting Worker. No hay commits ni cambios en el worktree original.
Docker, SQL, Flyway, brokers, servicios y E2E no se ejecutaron. El bundle 21eed1d no se modificó.

## Contrato implementado, independiente del dominio de operaciones

`AcademicCommand` es un record inmutable en cada módulo; ambos extremos se verifican contra el mismo
fixture `src/test/resources/contracts/academic-v1.json`. La duplicación evita cambiar dependencias y contratos
compartidos; futuras evoluciones deben mantener los dos codecs alineados mediante estas pruebas.
El esquema local propuesto está en `academic-command-v1.schema.json`, no en contracts/events.
No representa aprobación del contrato general de apuestas ni VAR V2.

| Campo | Tipo / regla |
|---|---|
| schema_version | entero JSON 1; el codec rechaza 1.0, versiones nuevas y claves duplicadas |
| command_id | texto no vacío; productor usa UUID determinista por namespace académico V1 y `(match_id, source_event_id)` con longitudes explícitas |
| match_id | texto no vacío; identidad original del partido |
| source_event_id | texto no vacío; event_id deportivo original |
| command_type | exactamente SUSPEND_BETS o RESUME_BETS |
| timestamp | instante ISO-8601; se copia del evento, **nunca es una secuencia** |

Exactamente seis campos snake_case, UTF-8 válido, tamaño máximo de entrada 65536 bytes.
El codec Java aplica validaciones adicionales al JSON Schema: rechaza blancos, claves duplicadas,
trailing tokens y timestamps no interpretables por Instant. La equivalencia para deduplicar es el JSON
canónico de los seis valores decodificados (no el orden de claves o un offset de timestamp equivalente).
Un cambio de tipo/match/fuente/timestamp bajo la misma identidad es conflicto, no reemplazo.

| Evento nuevo válido | Acción | Resultado académico Worker |
|---|---|---|
| GOAL | SUSPEND_BETS | SIMULATED_SUSPENDED |
| VAR_CHECK | SUSPEND_BETS | SIMULATED_SUSPENDED |
| GOAL_ANNULLED | RESUME_BETS | SIMULATED_RESUMED |
| MATCH_CONTINUES | RESUME_BETS | SIMULATED_RESUMED |

La referencia original de GOAL_ANNULLED NO es un séptimo campo: queda en
`academic_outbox.source_reference_event_id`, `source_payload` y en processed_event.reference_event_id.
Esto conserva evidencia fuente sin inventar semántica GOAL_ANNULLED V2.
La validación deportiva sigue siendo el decoder existente: no se amplía ni se sustituye el contrato de entrada.

## Recorrido funcional

1. El consumidor Kafka existente, cuando se autorice, decodifica el evento con el contrato actual.
2. `JdbcSuspensionStore.handle` mantiene su lock por partido y registro de identidad. Para cada evento
   nuevo válido, con `academic.commands.enabled=true`, inserta exactamente una academic_outbox en
   la misma TransactionTemplate que processed_event, estado y recibo. Un fallo revierte todo.
   No depende de una transición de estado: dos suspensiones consecutivas generan dos comandos nuevos.
   Duplicados, conflictos e inválidos no generan comandos académicos nuevos.
3. `AcademicOutboxDispatcher` selecciona filas pendientes con lock SKIP LOCKED, sin adelantar otra
   fila pendiente del mismo partido por source_version. Usa exchange por defecto `""`, routing=nombre de
   cola, mensaje persistente, mandatory, messageId=command_id y confirm correlacionado.
   Solo marca published_at tras ACK confirmado y ausencia de return. NACK, timeout, return, fallo de
   conexión o de persistencia retienen el comando. El destino queda persistido: cambiarlo exige revisión.
4. `AcademicWorkerListener` y `AcademicCommandHandler` son independientes de WorkerService.submit.
   `JdbcAcademicCommandStore` registra comando, resultado y entrega en una sola transacción. Un duplicado
   idéntico incrementa delivery_attempts y agrega un recibo; un conflicto preserva el resultado original.
   Resultado consultable internamente mediante `AcademicCommandStore.find(command_id)`, también con
   una nueva instancia del adapter. No se añade API REST.
5. ACK AUTO del contenedor solo después de retornar el listener: la transacción debe haber terminado.
   Malformados/conflictos se auditan con bytes originales y se envían a DLQ con confirmación antes de
   retornar. Fallos hacen throw; cuatro intentos con backoff, luego se detiene solo el listener académico
   y se solicita requeue. Reanudación tras subsanar el fallo requiere actuación del operador, no se descarta.

Garantía buscada: **at-least-once**, con efecto académico deduplicado. No es exactly-once.
Un crash después de confirmar Rabbit y antes de confirmar published_at, o después del commit Worker
antes del ACK, puede reentregar el mismo command_id. Los recibos y delivery_attempts crecen; el resultado
original no se reaplica. DLQ también puede duplicar ante confirmación incierta; conserva identidad y bytes.
`source_version` solo ordena pendientes del productor: no se incorpora como secuencia autoritativa al wire.

## Orden e independencia de apuestas

Cada resultado es una acción por comando; **no es el estado actual del mercado**.
Todas las acciones llevan `ordering_status=NO_AUTHORITATIVE_SEQUENCE`. El contrato no permite distinguir
un comando antiguo desconocido de uno nuevo usando timestamps, por lo que se registra esa incertidumbre
explícitamente; no se infiere precedencia ni se reabre nada. ORDER02 sigue abierto.
MarketState.apply, WorkerService.submit, MarketEvidence, AcceptancePolicy, grants, leases y V1/V2 históricos
permanecen idénticos. El listener académico rechaza configuración acceptance=true o authorization distinta
de disabled. SIMULATED_RESUMED nunca implica OPEN, ACCEPTED, permiso, grant o lease.

## Configuración: todo OFF por defecto

| Módulo | Variable | Predeterminado |
|---|---|---|
| Suspension | ACADEMIC_COMMANDS_ENABLED | false (sin nuevas escrituras académicas) |
| Suspension | ACADEMIC_PUBLICATION_ENABLED | false (sin dispatcher académico) |
| Worker | ACADEMIC_CONSUMER_ENABLED | false (sin listener/store académico Spring) |
| Worker | ACADEMIC_TOPOLOGY_ENABLED | false (sin Declarables ni RabbitAdmin académicos) |
| Ambos | ACADEMIC_QUEUE | betting_commands |
| Worker | ACADEMIC_SOURCE_ID | academic-local/1; reemplazar por identidad de ensayo acordada |

Confirm timeout predeterminado 2000ms, ajustable por propiedad `academic.confirm-timeout-ms`.
Las conexiones se heredan de las variables PostgreSQL/RabbitMQ ya existentes del módulo, sin nuevas
credenciales. Confirmaciones correlated y returns están configuradas; no abren conexiones por sí solas.
Los consumidores deportivos y de operaciones existentes conservan sus flags OFF, al igual que Flyway,
autoridad y aceptación. Activar flags académicos no autoriza activar el resto.
El runner académico Worker comprueba tablas con SELECT antes de iniciar su único listener.
El runner de publicación comprueba su tabla; el scheduler retiene ante errores. Readiness existente de
mercado no certifica la salud académica: el operador debe comprobar sus filas pendientes/recibos y logs.
No se generan comandos retroactivos para eventos ya procesados cuando el flag estaba OFF.

## Migraciones propuestas — NO ejecutadas

- Suspension V3__academic_commands.sql: academic_outbox, PK command_id, UNIQUE(match_id,source_event_id),
  UNIQUE(match_id,source_version), FK al evento procesado, índice parcial de pendientes, bytes y referencia fuente.
- Worker V3__academic_commands.sql: academic_command (PK e identidad de origen única, checks de tipos/resultados,
  contador de entregas) y academic_delivery (custodia de originales y conflictos, sin FK para no perder inválidos).
- Sin permisos PUBLIC. Sin cambios de V1/V2, tablas de autoridad, operaciones, leases o grants.
- Flyway sigue desactivado. Una ejecución futura requiere autorización, BD/roles aislados y migrador separado.
  Si se migra una base común nueva: control V1→V3 antes de worker V1→V3, con schemas separados y revisión de
  los requisitos V2 existentes. No copiar estos V3 encima de migraciones aplicadas sin verificar versiones/checksums.

## Topología y permisos mínimos PROPUESTOS — no creados ni certificados

Un vhost/broker de ensayo acordado y aislado, sin reutilizar la cola de comandos SimulatedCommand.

| Recurso | Declaración prevista |
|---|---|
| betting_commands | durable, no auto-delete, DLX=betting_commands.dlx, dead-letter routing=rejected |
| betting_commands.dlx | direct, durable, no auto-delete |
| betting_commands.dlq | durable, no auto-delete |
| Binding | betting_commands.dlx → betting_commands.dlq, key=rejected |
| Publicación | exchange por defecto vacío → routing betting_commands (binding implícito de cola) |

Identidades runtime no declaran recursos: ACADEMIC_TOPOLOGY_ENABLED=false incluso en un ensayo de consumo.
La declaración opcional está disponible pero exige permiso/credencial de provisionador separado aprobado por DEV1.

| Rol conceptual | Permisos PostgreSQL adicionales para este recorrido |
|---|---|
| control ingest | USAGE betting_control; INSERT academic_outbox (aparte de permisos preexistentes para procesar eventos) |
| control dispatcher | SELECT academic_outbox; UPDATE únicamente published_at; sin DELETE |
| worker académico | USAGE betting_worker; SELECT/INSERT academic_command; UPDATE únicamente delivery_attempts; INSERT academic_delivery; SELECT delivery_id para readiness |
| migrador | propietario DDL únicamente de su schema; nunca identidad runtime |

No conceder escritura de operation, operation_authorization, authorization_authority, authorization_lease ni
EXECUTE de funciones de autorización por habilitar este flujo. Mantener los permisos existentes restringidos.
Las propuestas de ACL Rabbit a validar con Sebastián son por recurso: productor publica solamente al exchange
por defecto; Worker lee solamente betting_commands y publica solamente a betting_commands.dlx; auditor DLQ
lee solamente betting_commands.dlq; configure vacío para runtime. La representación de permisos del exchange
por defecto, el binding de DLQ y el enforcement concreto deben verificarse con la versión/broker autorizado;
no se presentan como ACL ya probadas. No usar comodines amplios ni credenciales de provisionador en runtime.
La cola por defecto solo puede usarse después de demostrar ausencia de otros consumidores incompatibles.
El exchange por defecto no ofrece aquí una autorización demostrada por routing key: el vhost académico debe
ser exclusivo y no contener colas sensibles. Si se exige compartir vhost, hay que revisar con DEV1 un exchange
académico dedicado y sus bindings/ACL antes de activar; no basta afirmar que write sobre el exchange limita
el destino a una sola cola. Esta decisión de infraestructura está pendiente, no se modifica unilateralmente.

## Pruebas y evidencia ejecutada

TDD inicial: M01–M04 escritos antes del mapper; S01–S02 escritos primero como barreras sobre protecciones existentes.
RED real: M01–M04 = 4 ejecutadas, 4 fallos de aserción, 0 errores. S01–S02 = 2 exitosas desde el principio;
no se introdujeron fallos artificiales en seguridad. GREEN: M01–M04 = 4 exitosas; S01–S02 = 2 exitosas.
Hubo un fallo de compilación intermedio por constructor incompleto de JdbcSuspensionStore; se corrigió
preservando el constructor anterior. No se presenta ese fallo de compilación como RED de comportamiento.
Después se separaron mapper, codec, almacenamiento y transporte, y se añadieron pruebas de robustez.

| Grupo | Evidencia offline |
|---|---|
| M01–M04 | GOAL/VAR suspenden; anulación/continuación reanudan simulación |
| S01–S02 | política disabled permanece BLOCKED con OPEN; autoridad disabled no concede grant |
| AtomicOutbox | 7 pruebas: todos los eventos, fuente del gol, rollback, duplicado, conflicto, inválido y OFF |
| Publication | 7 pruebas: confirm, NACK, timeout, return, fallo de marker, cambio de destino, barrera por partido |
| W01–W03 | ambos resultados persistidos; commit previo a retorno; fallo de BD propaga |
| D01–D03 | duplicado idéntico, contenido conflictivo e identidad de origen reutilizada |
| E01–E04 | inválido preservado, messageId inconsistente, identidad durable ausente, orden incierto explícito |
| R01 | nueva instancia lee resultado persistido mediante JDBC mock; NO es un reinicio PostgreSQL certificado |
| Codec | 4 pruebas de tipos/versiones, campos, claves duplicadas, formato y tamaños |
| Listener | 7 pruebas de custodia, fallo de persistencia/DLQ, conflictos, destino y topología durable |
| Retry | 3 pruebas de presupuesto, recuperación y negativa a habilitar autoridad/aceptación |
| Wire | 1 prueba por módulo con fixtures idénticos productor/Worker |
| Contextos | OFF efectivo; sin RabbitAdmin ni beans académicos de transporte por defecto |

Regresión final Maven offline Java17, SpringBoot3.5.7, `mvn -o -Dmaven.repo.local=/private/tmp/live-jvg-archiver-m2 -f services/<módulo>/pom.xml verify`:

| Módulo | Total descubierto | Exitosas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|---:|
| betting-suspension-service | 160 | 157 | 0 | 0 | 3 |
| betting-worker | 157 | 140 | 0 | 0 | 17 |
| Total | 317 | 297 | 0 | 0 | 20 |

47 pruebas académicas offline exitosas nuevas; dos nuevas pruebas PostgreSQL preparadas y omitidas.
Las pruebas existentes omitidas no se presentan como realizadas. verify ejecutó tests, package y verificaciones
Maven sin descargar dependencias. Logs RED/GREEN/robustez/verify (archivos .txt, porque .log está ignorado) en docs/evidence/academic-work-queue
bajo cada módulo. En .txt solo se eliminó whitespace al final de línea; los originales .log se conservan localmente. Los logs de robustez anteriores al fixture y retry muestran 18+24; el verify final incluye 19+28.

**Cero ACCEPTED/grants:** evidencia offline = S01/S02, guard de configuración, prueba W02 que limita todo SQL
académico a tablas academic_* y regresión del dominio intacto. No existen contadores reales PostgreSQL de esta
funcionalidad. `AcademicPreparedPostgresTest` de Worker prepara assertions count(ACCEPTED)=0 y
count(operation_authorization)=0; el de control prepara count(authorization_lease)=0. Están OMITIDOS.
Ambos preparan Flyway V3, reinicio lógico de adapter, dedup y rollback inducido en tablas nuevas, protegidos por
`DEV3_RUN_ACADEMIC_POSTGRES_TESTS=true` y la aprobación/aislamiento estrictos de IsolatedPostgres existentes.
NO habilitarlos sin una autorización posterior específica; no se ejecutó ninguna de sus sentencias SQL.

## Riesgos, dependencias y próximo paso

1. Sebastián: revisar topología aislada, nombres, ACL Rabbit/default exchange, versiones V3 y roles mínimos;
   no se modifican Docker, Compose, preflight ni su bundle. Provisión y migraciones requieren autorización futura.
2. Gabriel: confirmar que Work Queue académica y Fanout live_alerts/Notification son independientes; no se toca
   Notification, Dashboard ni sus colas. No hay dependencia productiva nueva sobre esos módulos.
3. Persistencia/locks y ACK/confirms aquí se verifican offline con mocks; necesitan ensayo PostgreSQL/Rabbit real
   separado. No hay certificación de recuperación física, concurrencia real ni durabilidad de cola.
4. No hay secuencia autoritativa: resultados por comando, incertidumbre explícita y ORDER02 abierto.
5. Los recibos de entrega/DLQ crecen; retención y replay operativo requieren política revisada. No se purgan datos.
6. Cambios de cola entre despliegues se bloquean ante destinos persistidos distintos. Una confirmación incierta
   permite reentrega, no pérdida intencional. No reclamar exactly-once ni recuperación de orden arbitrario.

Dictamen: **LISTO PARA REVISIÓN del flujo académico offline**. Aceptación, autoridad y consumidores externos
siguen OFF. La siguiente autorización debe cubrir revisión de código/contrato local y posteriormente, por separado,
provisión aislada, migraciones y pruebas reales. No es una autorización de merge ni de E2E.
