# DEV 3 — Fase 5: preparación de integración completa

Fecha: 2026-11-16 (America/Bogota). Alcance: únicamente Historical Archiver, Betting Suspension Service y Betting Worker. No se ejecutaron brokers, contenedores o migraciones. Consumo, publicación y aceptación siguen desactivados.

## 1. Estado y protección

Rama inspeccionada: `integration/Valeria-mvp-dev`; HEAD inicial `9d8aae2` (merge externo de auditoría, ya presente al iniciar). Se conservaron los nueve cambios rastreados previos y todos los archivos nuevos de las fases anteriores. No había conflictos Git ni merge pendiente.

Antes de editar se creó `/private/tmp/live-jvg-dev3-phase5/modules-before.tar.gz` con fuentes/documentos de los tres módulos; cada miembro se verificó contra SHA-256. Inventario y estado previo en ese directorio privado. No contiene `.mvp/local.env`, secretos privados ni datos Docker/DB. Es respaldo temporal local, no un archivo compartido ni un backup de bases.

Para revisar/restaurar sin sobrescribir trabajo: extraer ese tar en **otro directorio vacío** y comparar los archivos necesarios. No ejecutar una restauración completa sobre el workspace actual. Los hashes del inventario externo se verifican nuevamente al cerrar; ver informe de resultados de esta fase.

## 2. Compatibilidad comprobada y cambios internos

| Servicio | Java/Boot | HTTP normal / perfil aislado | Esquema e historial | Transporte |
|---|---|---|---|---|
| Historical Archiver | 17 / 3.5.7 | 8082 / 18882 | archiver / archiver.flyway_schema_history | Kafka, grupo propio |
| Betting Suspension | 17 / 3.5.7 | 8083 / 18883 | betting_control / betting_control.flyway_schema_history | Kafka propio; outbox Rabbit apagado |
| Betting Worker | 17 / 3.5.7 | 8084 / 18884 | betting_worker / betting_worker.flyway_schema_history | Rabbit apagado; sin Kafka |

Dependencias administradas por Boot: Spring Kafka 3.3.10 donde corresponde, Spring Rabbit 3.2.8 donde corresponde, PostgreSQL driver 42.7.8 y Flyway 11.7.2. Sin cambios POM de esta fase, ORM ni nuevas tecnologías. La JVM de Maven se fija explícitamente a Temurin 17, pues el Java predeterminado del equipo puede ser distinto.

Correcciones:

1. **Identidad del archiver:** el núcleo y Suspension usan `(match_id,event_id)`, pero Archiver tenía unicidad global. Se adapta JDBC a identidad por partido y se agrega **V2**, conservando V1. V2 completa match_id de recibos existentes, reemplaza FK/UNIQUE y conserva secuencias/payloads. No se ejecutó ninguna migración. Esta versión requiere V2 antes de iniciar consumo.
2. **Correlación VAR conservadora:** una anulación de un gol no correlacionado con una revisión pendiente marca inconsistencia, aunque existan revisiones de otro gol. No podrá resolverse abriendo tras cerrar una revisión ajena.
3. **Sin fallback inseguro:** nueva regresión del worker comprueba que un fallo de la fuente no reutiliza OPEN persistido aunque su TTL todavía parezca vigente.
4. **Preparación aislada:** perfiles y ejemplos de variables con conexiones obligatorias, puertos diferenciados, flags de efectos externos a false, probes Boot y readiness que incluye DB.

## 3. Matriz de contratos y dependencias

“Observado” significa confirmado en código/fixtures, **no aprobación formal del schema compartido**. Los cuatro JSON compartidos continúan vacíos.

| Flujo | Situación | Identidad/versiones | Campos/reglas | Dependencia bloqueante |
|---|---|---|---|---|
| Productor → match_events | Formato observado; fuente real Kafka | Clave match_id; evento identificado por match_id/event_id; sin schema_version en wire actual | event_id, match_id, event_type, minute entero 0..130, timestamp Instant; team_id obligatorio para GOAL; reference_event_id obligatorio para GOAL_ANNULLED; opcionales nullable | Aprobar schema/evolución y retención; mantener IDs no UUID válidos |
| match_events → Archiver/Suspension | Decoders locales compatibles con fixtures históricos del productor | Grupos independientes historical-archiver / betting-suspension-service; recibo source/topic/partition/offset | Snake_case estricto, bytes y headers conservados; campos desconocidos a cuarentena | Grupos/fuentes exclusivos del entorno; sin reutilizar match-state-service |
| Match State → live_updates | Formato observado | event_id y match_id; no versión monotónica pública | match_id, home_score, away_score, event_id, event_type, updated_at, var_status, reference_event_id; exchange topic, key score.{matchId} | No es autorización de apuestas; no sustituir correlación por var_status CLEAR |
| VAR_CHECK / MATCH_CONTINUES | Tipos observados; cierre ambiguo | VAR_CHECK tiene event_id; no review_id/closure_id contractual | La referencia opcional no demuestra qué revisión termina; llegada fuera de orden posible | Aprobar autoridad y correlación de cierre; mantener desactivada reanudación automática |
| ReviewClosure interno | Propuesta implementada sin adaptador externo | command_id/partido y expectedVersion | reviewId, continuationEventId, actor/motivo; continuación observada después de revisión; no inconsistencia | Aprobar caller, política y auditoría; no endpoint nuevo en esta fase |
| Suspension → Rabbit | Propuesta local, publicación apagada | message_id estable; schema_version=1 local; version del agregado puede tener saltos | BETTING_SUSPENDED / BETTING_RESUMED, status, bets_allowed, source_event_id, cause_id, updated_at | Aprobar payload/exchange/routing, consumers y semántica de UNKNOWN; no conectar directamente al worker |
| Emisor simulado → Worker | Propuesta local; no productor aprobado | command_id y operation_id globales en worker; schema_version=1 local | match_id, simulated_user_id, timestamp; sin campos financieros ni evidencia aportada por el comando | Aprobar emisor, comandos, topología e identidad de negocio |
| Suspension → evidencia worker | **No existe contrato seguro aprobado** | Autoridad, epoch y revisión de evidencia distintos de simples timestamps | OPEN vigente, emisión/vencimiento y fuente confiable; fuente desconocida/fallida bloquea | El GET/outbox actual no prueba freshness ni autoriza atómicamente; sustituir lectura por autorización delimitada |

Se revisaron `MatchEvent`, `KafkaMatchEventPublisher`, `OutgoingReferencePolicy`, `ScoreUpdate` y propiedades del núcleo. El productor mantiene clave matchId y snake_case; su validación de referencias nuevas no transforma MATCH_CONTINUES en cierre inequívoco. No se añadieron campos al wire deportivo.

## 4. Solución propuesta para la carrera: autorización PostgreSQL atómica

**Recomendación MVP, pendiente de aprobación/implementación:** una misma **base** PostgreSQL, esquemas separados, y un componente de autorización delimitado que participa en la transacción de la operación del worker. Compartir instancia/servidor con bases distintas no permite esta transacción local: hoy los defaults apuntan a nombres de base diferentes.

### Autoridad y orden de locks

- `betting_control.market_state` sigue siendo la fuente de verdad. Suspension ya bloquea esa fila con FOR UPDATE al aplicar evento/cierre.
- El worker debe sustituir el permiso basado solo en `SuspensionEvidencePort.fetch` por una operación de **autorización**, no añadir una comprobación HTTP a su lectura actual.
- Una función SQL controlada, o componente JDBC igualmente delimitado, bloquea **la misma fila** dentro del `DataSource`/conexión/transacción que guardará la operación. No usar REQUIRES_NEW, una segunda conexión o un commit previo del permiso.
- Contrato propuesto de autorización: command_id, operation_id, partido, digest del comando y versión esperada; resultado durable idempotente y versión de mercado autorizada. Aprobarlo antes de agregar tablas/funciones o rutas.
- Orden uniforme: reclamar identidad de comando → bloquear mercado autoritativo → validar autorización/operación → guardar operación/evidencia/resultado → commit → ack. Un guard local, si se conserva, se toma después del mercado autoritativo; no invertir el orden entre rutas.
- Validar bajo el lock: OPEN, sin inconsistencia, sin revisiones pendientes, versión válida y evidencia/liveness de autoridad no vencida. Ausente, UNKNOWN, SUSPENDED, lease vencido o error: bloquear.
- Crear decisión de autorización ligada a identidad/digest y resultado del worker en el mismo commit. Rollback elimina ambos efectos. Duplicado recupera la primera decisión y no vuelve a autorizar una intención terminal.
- Permisos explícitos por esquema. Si se usa SECURITY DEFINER: dueño mínimo, search_path fijo, nombres cualificados y sin EXECUTE público. No conceder escritura general desde worker sobre el estado del mercado. La identidad del caller debe ser auditable, no un flag aportado por el comando.

### Garantía exacta

Una suspensión cuyo commit terminó **antes de que el worker obtenga el lock autoritativo** será vista como SUSPENDED/UNKNOWN y bloqueará la aceptación. Si el worker obtuvo primero el lock y valida OPEN, la suspensión espera; la operación debe terminar commit/rollback antes de que se confirme esa suspensión. Ese orden define la linealización, no el instante físico del gol/VAR ni el momento de envío del mensaje Kafka.

La freshness no puede obtenerse de un updated_at histórico: proponer una concesión/liveness renovable de la autoridad, con reloj DB y expiración corta, renovada únicamente cuando el consumidor esté sano bajo una política aprobada. Su escritura participa en el mismo orden de lock. No debe reabrir revisiones ni desconocer una suspensión. Definir cómo se detecta lag/caída de la fuente: un heartbeat aislado no prueba que no haya un VAR todavía en tránsito.

Esto **no** garantiza que todo evento deportivo ya publicado pero aún no procesado por Suspension invalide una decisión. Si el negocio exige orden con ese punto, también debe secuenciar autoritativamente eventos deportivos e intenciones. La garantía solicitada sobre **suspensión confirmada** sí es implementable con el lock/commit común descrito.

### Pruebas que deben aprobarse y ejecutarse antes de habilitar

1. Dos conexiones reales: suspensión obtiene lock, cambia estado y hace commit; aceptación en espera debe bloquearse.
2. Orden inverso: aceptación obtiene lock; suspensión no puede confirmar hasta terminar aceptación. Registrar el orden observable de commits.
3. Rollback/caída en cada punto: no puede quedar autorización confirmada sin la operación o aceptación sin autorización.
4. Duplicados concurrentes y digest conflictivo: una decisión lógica por identidad, sin reejecutar.
5. Revisión obsoleta, varias revisiones VAR, UNKNOWN, pérdida de liveness/lease y reinicio: bloquear.
6. Verificar permisos, misma conexión efectiva y aislamiento; ninguna ruta ACCEPTED puede omitir el componente atómico.

No se implementó ni aprobó ese componente en esta fase. El camino actual de aceptación por evidencia sigue apagado y **no se presenta como solución completa de la carrera**.

### Alternativa con bases separadas

Si no puede compartirse base/transacción: una autoridad única serializa suspensión y decisión de autorización en su DB, con inbox/outbox y resultado idempotente; el worker proyecta/ejecuta decisiones ya finalizadas, no acepta independientemente.

Un ticket emitido antes de suspender no basta: debe consumirse/finalizarse de forma atómica en la autoridad, verificando estado/versión y vencimiento en ese instante. Aceptación anterior a suspensión se conserva como decisión anterior, no se reinterpreta como una apuesta nueva posterior. Una caída entre decisión autoritativa y proyección worker deja reconciliación pendiente mediante reentrega, no una transacción distribuida atómica. Requiere aprobar protocolo y semántica de finalización; más cambios que compartir una base para el MVP. Hasta entonces mantener BLOCKED/UNKNOWN.

## 5. Preparación de entorno, perfiles y health

En cada módulo:

- `src/main/resources/application-dev3-integration.properties`, sin activación por defecto.
- `docs/integration.env.example`, sin passwords ni direcciones DB utilizables por accidente.
- `docs/INTEGRATION.md`, instrucciones específicas.
- Probes `/actuator/health/liveness` y `/actuator/health/readiness`; en el perfil aislado readiness incluye DB. `/actuator/health` es el health global habitual.

Health UP comprueba disponibilidad, no ausencia de lag ni autorización para apostar. Un listener pausado puede coexistir con DB sana: revisar logs/estado de consumo y política de autoridad antes de cualquier aceptación.

El perfil aislado mantiene false: Flyway, consumidores, publicación y aceptación. También requiere URLs y credenciales explícitas DEV3_*, evitando defaults que apunten a recursos actuales. Un operador puede sobreescribir propiedades por CLI: esos overrides son acciones posteriores con autorización, no una barrera criptográfica ni permisos implícitos.

No se cambió el Compose principal. No crear automáticamente schemas, roles, topics, exchanges ni colas. Acordar con el equipo recursos exclusivos y verificar nombres efectivos de red/volúmenes, endpoints, usuarios y grupos antes de cualquier prueba real.

## 6. Pruebas preparadas, no ejecutadas

Cada módulo incorpora `PreparedPostgresIntegrationTest`, omitido normalmente. Antes de la primera conexión exige:

- `DEV3_RUN_POSTGRES_TESTS=true`.
- `DEV3_IT_MIGRATION_APPROVAL=I_APPROVE_ISOLATED_MIGRATIONS` (solo después del permiso real del responsable; el texto de variable no lo sustituye).
- URL local explícita, puerto no estándar y no usado por el MVP/entornos anteriores, base NUEVA con prefijo específico `dev3_it_<schema>_<token>`, usuario `dev3_it_*` y password por entorno.
- Esquema del servicio inexistente: rechaza reutilizar una base ya migrada.

La prueba entonces aplicará Flyway en esa base autorizada: archiver verifica upgrade V1 con datos a V2, identidad por partido y recuperación; suspension verifica revisiones/cierre explícito/idempotencia/outbox; worker verifica operaciones bloqueadas y fence contra OPEN antiguo. No publican mensajes ni habilitan aceptación. No crean contenedores o DB, no borran esquemas/filas y no limpian volúmenes. Dejan evidencia/datos para inspección. Ejecutarlas de nuevo requiere otra base nueva aprobada.

Son pruebas de repositorio PostgreSQL preparadas. **No son aún una prueba del flujo completo de tres procesos por Kafka/RabbitMQ**, ni los escenarios de concurrencia atómica pendientes de la sección 4. Los contratos/topologías faltantes impiden afirmar una integración E2E lista para habilitar aceptación.

## 7. Dependencias DEV 1, DEV 2 y autorizaciones exactas

| Autorización/decisión | Responsable y efecto |
|---|---|
| Aprobar MatchEvent/ScoreUpdate y evolución | DEV 1/equipo: documentar campos, identidad por partido, retención/replay y referencias; sin modificar contratos desde DEV 3 |
| Aprobar correlación y autoridad de cierre VAR | Responsable/equipo: distinguir decisiones múltiples/ambiguas; decidir si se añade contrato o se conserva cierre interno auditado |
| Aprobar comando simulado y topología Rabbit | DEV 3/equipo: emisor, command_id/operation_id, ventanas, exchange/queue/routing propios y deduplicación; ninguna cola del dashboard se reutiliza |
| Elegir autorización atómica o autoridad separada | Responsable: misma base con esquemas/función delimitada y permisos, o protocolo alternativo; aprobar implementación y pruebas de carreras antes de aceptar |
| Autorizar DB/roles y migraciones | Infra/responsable: bases nuevas aisladas, permisos, V1/V2 y evidencia; autorización por separado para cualquier base compartida |
| Autorizar pruebas reales de brokers/procesos | Responsable: endpoints/topologías/grupos propios, redes/volúmenes verificados; escenarios no destructivos y reinicios solo de recursos dedicados |
| Coordinar con DEV 2 | Mantener API/UI/WebSocket y cola live_updates existentes; decidir solo futuras consultas de historia/estado/operaciones; no requieren cambios ahora |
| Habilitar consumidores/publicación/aceptación | Permisos distintos: consumir no implica publicar y ninguno implica aceptar. Aceptación solo tras solución atómica aprobada y pruebas reales satisfactorias |
| Commits/PR/push | No autorizados en esta fase; el trabajo permanece local para revisión |

## 8. Resultado y siguiente paso

Las correcciones internas y preparación local se completan sin activar infraestructura. El informe de ejecución de los tres módulos, cambios exactos, hashes y conteos está en [phase-5-results.md (histórico archivado)](https://github.com/sebastianUCC2024/Live-JVG/blob/ed1ac2193a4adf863e772c040ef0431a4a7b3fd6/services/betting-suspension-service/docs/phase-5-results.md). Los informes de fases 2–4 conservan sus conteos históricos; no confundirlos con el total actualizado ni sumar ejecuciones repetidas.

Siguiente paso: revisar decisiones/contratos y autorizar explícitamente implementación de la autorización atómica y pruebas reales aisladas. No activar el camino OPEN basado solo en lectura para “probar que funciona”.
