# DEV 3 — Preparación avanzada de integración

Fecha: 2026-11-16. Rama: `feature/Valeria-dev3-e2e-preparation`, creada desde
`2e53fdece51e6e4ec08782dec8f31b30630c805e`. Árbol inicial limpio. Sin commits ni push.
PR #20 confirmado MERGED mediante GitHub CLI; merge `7eb2de42524c6f65e4e89e7996f001eeec7315c0`.
Los cinco documentos de dashboard añadidos después son propuestas; no aprueban contratos.

## Resultado y límites

Archiver, Suspension y Worker compilan y pasan Maven verify. Se conservan sus componentes
funcionales: inbox/custodia, deduplicación durable, outbox, cierres internos V1/V2 y autorización
JDBC. Se implementaron readiness de transporte, observación de outbox, configuración opt-in
para una base común y pruebas pasivas de brokers desactivadas. No se modificaron SQL,
roles, autorización, contratos, dependencias, Compose ni módulos ajenos.

**Preparado para integración coordinada; E2E y aceptación no habilitables todavía.**
Los indicadores describen condiciones locales, no certifican frescura ni decisiones de negocio.

## Base común y entrega a DEV 1

Seleccionar, en ese orden, `SPRING_PROFILES_ACTIVE=dev3-integration,dev3-authority`
para Suspension y Worker. El segundo perfil exige la misma `DEV3_AUTHORITY_DB_URL`, sin
fallback. Las credenciales siguen separadas: `DEV3_CONTROL_DB_USERNAME/PASSWORD` y
`DEV3_WORKER_DB_USERNAME/PASSWORD`. Archiver mantiene su base independiente.
Los perfiles dejan Flyway, consumidores, publicación, aceptación y modo atómico apagados.
El orden de perfiles importa; los permisos de activación son decisiones separadas.

No provisionar la base de laboratorio ni el usuario del MVP. DEV 1/DBA deben elegir una base
NUEVA dedicada y revisar en ambas conexiones `current_database()` y el catálogo autoritativo.
Una URL común por configuración no prueba roles, catálogo ni conexión reales.

Secuencia de instalación, no ejecutada:

1. Crear DB nueva, owners NOLOGIN y logins limitados, distintos del migrador.
2. Migrar Suspension V1/V2 con historial en `betting_control`; después Worker V1/V2 con
   historial en `betting_worker`. No habilitar Flyway con el login de aplicación ni migrar ambos
   historiales en el esquema public. Usar credenciales privadas del migrador fuera de Git.
3. Asignar propietarios de cada tabla, función y trigger. Aplicar y auditar grants de
   `phase-6-atomic-security.md`, sección 4, y contrastar el laboratorio en
   `evidence/postgres-certification/roles.json`. No usar GRANT ALL ni ownership de app.
4. Confirmar restricciones diferidas y funciones `lock_market_authority` y
   `record_atomic_authorization`; Worker no debe escribir directamente estado ni grants.
5. Leer epoch persistente de `betting_control.authorization_authority`; no generar uno por
   proceso. Comparar DB/epoch contra `BETTING_WORKER_AUTHORITY_DATABASE/EPOCH`.
   Modo `disabled` permanece hasta aprobación de autoridad, leases y activación.
6. Ejecutar certificación restringida y validar recuperación antes de autorizar consumidores.

Los puertos de preparación son 18883/18884; Archiver conserva su perfil independiente.
`integration.env.example` de ambos módulos contiene variables vacías y comentarios; no es
un archivo de credenciales ni aprovisiona recursos. DEV 1 puede trasladarlas a Compose.
No se modificó Compose. Rabbit exige vhost explícito `DEV3_RABBITMQ_VHOST` y destinos
`DEV3_CONTROL_EXCHANGE/ROUTING_KEY`, `DEV3_WORKER_EXCHANGE/QUEUE`; ninguno se declara
ni se aprueba por seleccionar el perfil.

## Kafka: garantías existentes y límites

`ArchiveKafkaListener` y `SuspensionKafkaListener` reciben bytes y conservan posición,
clave, payload y headers. Configuraciones de ambos usan RECORD, auto-commit false y una
concurrencia; la llamada al store termina su transacción antes del retorno/ACK del listener.
No hay transacción distribuida PostgreSQL–Kafka: caída después de commit y antes de offset
causa reentrega; el ledger/inbox durable debe deduplicar.

`JdbcSuspensionStore` distingue posición `(source_id,topic,partition,offset)` e identidad
`(match_id,event_id)` y contenido canónico. No reutilizar source_id para otro log o clúster.
Los inválidos custodiados pueden avanzar; un fallo de persistencia no puede ACKear custodia
inexistente. Los handlers agotan cuatro entregas y pausan conservando offsets; no descartan
ni reanudan automáticamente. Readiness ahora refleja esa pausa.

Particionar por match_id exige acuerdo del productor; una sola concurrencia local no crea
orden global entre particiones. Kafka no resuelve referencias fuera de orden por sí solo.
Tests de listeners/handlers/decoder/store ya existentes pasan en verify; son locales, no
certifican offsets de un broker real.

## Rabbit y contrato exacto esperado por Worker

Outbox existente: `OutboxDispatcher` toma la versión inédita más antigua por partido;
`RabbitSuspensionPublisher` exige confirm y mandatory/returns antes del marcador publicado.
Un timeout conserva la fila. Confirmación incierta puede reentregar el mismo message_id;
no se promete exactly-once. El receptor de mercado también debe deduplicar/versionar.

Worker solo registra su adaptador si `worker.consumer.enabled=true`; aun entonces su
listener tiene autoStartup=false. Usa AUTO ACK después de que `WorkerService.receive`
retorne del store transaccional; cuatro intentos, luego stop/requeue. Inválidos y exchange
incorrecto se custodian antes de ACK; fallos DB retienen entrega. No hay descarte DLQ nuevo
ni topología inventada. Recuperación requiere operador y comprobación de custodia/offset.

Propuesta local que entiende `JsonCommandDecoder` (NO contrato aprobado):

```json
{"schema_version":1,"command_id":"<nuevo>","operation_id":"<nueva>","match_id":"<partido>","simulated_user_id":"<usuario-simulado>","timestamp":"<instante-UTC-actual>"}
```

Todos los campos son obligatorios, nombres exactos snake_case, versión entera 1, cadenas
no vacías e instante ISO-8601; no se admiten otros campos, claves JSON repetidas, UTF-8
inválido ni documentos concatenados. El comando no aporta autorización ni dinero.
MessageId/headers/exchange/routing se preservan como evidencia de transporte. No elegir
exchange/routing definitivos hasta aprobación. El adaptador verifica exchange esperado.

command_id identifica el comando; operation_id identifica la operación. Reentrega idéntica
recupera el resultado; reutilización con contenido diferente es conflicto, no nueva apuesta.
Consultar `GET /api/betting/operations/{operationId}`; no se añadió POST ni productor nuevo.
Resultados: RECEIVED, ACCEPTED, REJECTED, BLOCKED, FAILED según el flujo existente;
con defaults seguros no hay aceptación. Infraestructura fallida provoca rollback y retry,
no un ACK artificial. Un reintento no debe modificar identidad ni timestamp originales.
La ventana existente de antigüedad del comando es 5 minutos.

## VAR V2 y autoridad de leases

Se reutiliza `SuspensionService.closeReviewV2` / `ReviewClosure.ReferenceMode.V2_REVIEW`;
V1 usa explícitamente V1_GOAL. No se deriva una versión a partir de un wire sin versión.
`VarV2MarketClosureTest` y `JdbcSuspensionStoreTest` verifican cierre individual, otras
revisiones pendientes, bloqueo GOAL independiente, referencias incorrectas, versión atrasada,
idempotencia y no reapertura por un MATCH_CONTINUES recibido por Kafka.
No se conectó un cierre automático ni se inventó cómo GOAL_ANNULLED representa gol y revisión.
Pendiente aprobación de envelope/versionado y confirmación de Gabriel para el mapeo externo.

`LeaseSourceVerifier` recibe matchId, versión y checkpoint opaco bajo el lock de mercado.
`UnavailableLeaseSourceVerifier` sigue denegando. `JdbcOpenLeaseIssuer` no es bean,
endpoint ni scheduler; no existe renovador de leases. Su integración no se activó.
Un checkpoint almacenado no prueba frescura. El contrato de autoridad futura debe definir:

- Fuente y cobertura por partido/partición, frontera completa procesada y cómo se acredita.
- MatchId, versión y epoch vinculados al checkpoint; cambios de fuente/epoch invalidan prueba.
- Frescura, reloj y expiración (emisor actual limita TTL a 5 s), revocación y replay.
- Comportamiento ante caída, timeout, lag, reasignación, nueva revisión y reinicio.
- Verificación acotada mientras se mantiene el lock; evitar llamadas remotas sin límite.
- Relación entre recepción física de VAR, commit de suspensión y el punto de autorización.

Matriz preparada para el futuro adaptador: evidencia desconocida/malformada/caducada,
partido distinto, versión/epoch distintos, checkpoint anterior, fuente caída o atrasada,
replay tras reinicio ⇒ denegar sin emitir lease; evidencia aprobada válida ⇒ verificar
vínculo bajo lock y rollback conjunto ante error. Repetir con fuente real, sin mocks de
confianza. Los tests actuales del issuer cubren denegación, caída, versión, pendientes,
orden del verificador bajo lock y rollback lógico. No prueban una autoridad real aún ausente.

## Política de replay histórico

Con auto-offset-reset=earliest, un grupo nuevo puede recorrer todo el log retenido. Grupo
existente reanuda offset; una DB vacía y offsets avanzados no reconstruyen estado completo.
No activar en esa combinación. Verificar retención y cobertura antes de definir baseline.

Archiver: reprocesar evidencia con source_id estable para el mismo log; duplicados idénticos
no crean históricos nuevos, conflictos se auditan. Restaurar primero DB durable si existe.
Suspension: estado ausente UNKNOWN; GOAL y cada VAR crean bloqueos independientes.
Los cierres internos exigen versión/evidencia vigente; un histórico MATCH_CONTINUES no
abre automáticamente. Ningún histórico CLEAR, BETTING_RESUMED ni updated_at emite lease.
El epoch de la autoridad no se cambia en un replay normal. Reconstrucción en DB nueva
requiere nueva autoridad aprobada y fencing; nunca copiar permisos históricos como vigentes.

Opciones: restaurar backup y reentregar desde checkpoint durable (preferida), o reconstruir
una proyección aislada y compararla antes de cutover aprobado. No retroceder offsets de
producción, borrar guards, vaciar inbox, fabricar cierres ni editar ledger para reconciliar.
Partidos con referencias históricas incompletas quedan bloqueados y auditables.

Aceptación de replay: duplicado exacto conserva versión; conflicto conserva custodia;
revisión vieja/continuación atrasada no libera bloqueo posterior; reinicio no reutiliza lease;
DB nueva + offsets avanzados se rechaza operacionalmente; cobertura incompleta no autoriza.
Los casos de dominio/store existentes cubren los primeros invariantes; cutover/retención y
reconstrucción desde Kafka quedan pendientes de ensayo real aislado.

## Observabilidad implementada

`/actuator/health/liveness`: solo proceso; usar este grupo para reinicio del contenedor.
`/actuator/health/readiness`: disponibilidad Spring + DB + messagingReadiness; Suspension
agrega marketOutbox. Transporte OFF ⇒ OUT_OF_SERVICE/503, aunque liveness siga 200.
Actualizar la política de despliegue: no reiniciar por readiness ni exigir readiness UP a un
servicio deliberadamente inactivo. Los detalles HTTP siguen ocultos (show-details=never).

Kafka: missing/stopped/paused/no assignment impide readiness. Rabbit: listener ausente,
detenido o sin consumidores activos también. Los indicadores no arrancan/resumen listeners
ni conectan brokers. Assignment/consumer count son señales locales; pueden existir ventanas
hasta detectar una desconexión. No prueban recepción reciente, lag cero ni leases válidas.
`lag_verified=false` / `betting_authorized_by_health=false` evitan esa equivalencia.

Outbox: consulta agregada de pendientes y edad más antigua, sin payload/identidades.
DB inaccesible ⇒ DOWN sin exponer excepción; backlog por sí solo no inventa umbral de fallo.
`broker_verified=false`: fila publicada o backlog vacío no prueban disponibilidad actual.
El agregado puede tener costo con backlog grande; observar índice existente y latencia.

Reintentos agotados se conservan en logs existentes y ahora en listener PAUSED/STOPPED.
Confirm/return/timeout conservan backlog. Rechazos de autoridad siguen en resultados y
persistencia de operación; no se añadieron métricas con matchId ni payload como etiquetas.
Lag real, alertas, último confirm y disponibilidad de autoridad siguen pendientes de la
fuente/monitorización aprobadas; readiness UP nunca habilita apuestas.

## Pruebas y E2E pendiente

Resultados exactos y archivos: `evidence/advanced-preparation-results.json`,
`advanced-preparation-files.txt` y `evidence/advanced-preparation-maven.txt`.
Maven verify ejecuta tests y package. PostgreSQL gated no se ejecutó esta vez; no hubo cambios
SQL/roles/autorización y sus checksums/evidencias previas se conservaron. Ver certificación
anterior 17/17 en `postgres-real-certification.md` y `evidence/postgres-certification/`.
No es una nueva certificación de despliegue, leases o brokers.

Tests nuevos `PreparedKafkaBrokerIntegrationTest` (Archiver/Suspension) y
`PreparedRabbitBrokerIntegrationTest` (Worker) están listos pero omitidos por defecto.
Requieren recursos ya provisionados exclusivamente de prueba, nunca los de Fase 7E ni el
laboratorio PostgreSQL. El probe Kafka exige loopback, puerto distinto de 9092 y topic
`dev3_it_*`; Rabbit loopback/puerto distinto de 5672, vhost explícito y cola/exchange
`dev3_it_*`. Esos prefijos son guardas de tests, no contratos productivos.
No crean topología, consumen, publican, cambian grupos ni borran recursos. No prueban E2E.

Solo después de confirmar propiedad/aislamiento y permiso, con conexiones/credenciales
inyectadas privadamente, los comandos preparados son:

```bash
DEV3_RUN_ISOLATED_BROKER_TESTS=true DEV3_BROKER_APPROVAL=I_APPROVE_ISOLATED_BROKER_PROBES \
  mvn -f services/historical-archiver/pom.xml -Dtest=PreparedKafkaBrokerIntegrationTest test
DEV3_RUN_ISOLATED_BROKER_TESTS=true DEV3_BROKER_APPROVAL=I_APPROVE_ISOLATED_BROKER_PROBES \
  mvn -f services/betting-suspension-service/pom.xml -Dtest=PreparedKafkaBrokerIntegrationTest test
DEV3_RUN_ISOLATED_BROKER_TESTS=true DEV3_BROKER_APPROVAL=I_APPROVE_ISOLATED_BROKER_PROBES \
  mvn -f services/betting-worker/pom.xml -Dtest=PreparedRabbitBrokerIntegrationTest test
```

Ensayo E2E posterior, no ejecutado: DB NUEVA común y Archiver independiente → migrar/grants
→ provisionar topics/colas dedicadas → publicar IDs nuevos con clave match_id → verificar
custodia antes de offsets → duplicados/conflictos/invalidaciones → pausar y recuperar DB
→ confirmación incierta/outbox/reentrega → consulta durable de operación BLOCKED → pruebas
VAR V1/V2 aprobadas. Comparar estado, evidencias, offsets y mensajes; HTTP 200 no basta.
ACK/reentrega/confirm reales necesitan este ensayo; los probes pasivos no los sustituyen.
Activación de aceptación es otra decisión posterior, con autoridad verificable y certificada.

## Responsables y decisiones pendientes

DEV 1: base nueva común/roles/Flyway/epoch, Compose, topología aislada y permisos, orden y
retención Kafka, cobertura/checkpoints/frescura de fuente, contratos VAR con Gabriel y
activaciones graduales. DEV 2: confirmar destinatarios del estado de mercado y productor de
comandos simulados o asignar responsable; acordar versionado/topología y consulta de resultados.
No se presume que Dashboard o Notification ya produzcan comandos.
Equipo: aprobar comandos, estados de mercado y correlación GOAL_ANNULLED/revisión,
semántica de reapertura, autoridad/lease y punto de decisión. Después, certificar E2E y revisar PR.
Ninguna aprobación se deduce de documentos de propuesta o de que verify pase.
