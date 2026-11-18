# Live-JVG — aceptación académica V1

Fecha de consolidación: 18 de noviembre de 2026, America/Bogota.
Base de este cierre: dev `a50ba417f960260624c1313b2b6321033bda100a` (merge del PR #44). La compatibilidad VAR se implementó sobre 7c7d727; su validación real posterior se identifica como VAR_JPA_KAFKA. Las demás evidencias conservan sus revisiones originales.
Este documento propone la aceptación del alcance académico demostrado; no aprueba contratos ni excepciones en nombre del profesor y no certifica producción.

## Fuente y estados

El equipo confirmó los requisitos oficiales: Kafka con clave match_id, VAR con annuls_event_id, ZooKeeper, RabbitMQ Topic/Fanout/Work Queue, PostgreSQL/JPA, TDD, SOLID y Docker con límites de recursos. Se evalúan como requisitos confirmados, no se marcan pendientes por falta del texto. El usuario aclaró que el archivo completo de 129 líneas «Texto pegado(20261118-053352).txt» está en su conversación y aún no es accesible localmente; queda pendiente únicamente el cotejo literal que necesite leer esas líneas, sin inventar citas ni cláusulas adicionales. La [matriz histórica](integration/local-academic-v1/report.md) conserva sus resultados, no sustituye el texto oficial.

PASS acredita únicamente el alcance indicado por su evidencia. PARCIAL indica límites técnicos o cobertura incompleta; PENDIENTE requiere decisión/evidencia; DIFERENCIA LITERAL identifica una divergencia terminológica o tecnológica reportada en las fuentes secundarias. Una ejecución histórica no es una ejecución nueva sobre el dev actual.

## Matriz de trazabilidad

| Requisito confirmado / alcance declarado | Implementación | Prueba / evidencia existente | Estado y límite |
|---|---|---|---|
| Java 17 y microservicios | Siete módulos con POM independiente | Clon limpio CLONE: siete verify, 885 aprobadas, 39 omitidas, cero fallos/errores | PASS del ciclo local; no todos los servicios usan la misma versión Spring Boot |
| Kafka y publicación por partido | [KafkaMatchEventPublisher](../services/live-feed-producer/src/main/java/com/live_jgv/live_feed_producer/infrastructure/kafka/KafkaMatchEventPublisher.java): clave event.matchId(); topic match_events y grupos independientes | E2E histórico E2E; laboratorio JPA_KAFKA con siete posiciones y offset confirmado 7 | PASS del recorrido ensayado; orden Kafka por partición, no orden global |
| ZooKeeper | Compose declara Kafka 3.9.1 KRaft broker/controller, sin ZooKeeper | [Compose](../docker-compose.yml) y documentación del laboratorio | DIFERENCIA LITERAL pendiente del profesor: KRaft funciona en los laboratorios reales, pero no sustituye el ZooKeeper exigido sin aceptación explícita; no se cambia topología |
| GOAL, VAR_CHECK, GOAL_ANNULLED, MATCH_CONTINUES y corrección de marcador | Dominio Match State y codecs; anulación referencia el gol original | [MatchStateTest](../services/match-state-service/src/test/java/com/live_jgv/matchstate/domain/model/MatchStateTest.java), E2E: 1-0 CLEAR → 1-0 IN_PROGRESS → 0-0 CLEAR → 0-0 CLEAR | PASS de secuencia V1 controlada |
| Campo de referencia VAR | Lectores deportivos aceptan annuls_event_id y reference_event_id como una referencia; contradicciones rechazadas. Producer conserva emisión reference_event_id | [Schema MatchEvent](../contracts/events/match-event.schema.json), tests locales VarFieldCompatibilityTest y replay de adaptadores | PASS de lectores locales y lectura/archivo JPA con Kafka real en RUN f835870c8e96; PARCIAL de emisión literal hasta transición coordinada. No hay E2E nuevo |
| Contratos JSON | Cuatro schemas compartidos implementados; comando académico separado del comando de operaciones | [Contratos](../contracts/events), validadores y 84 tests Python del clon limpio | PASS estructural; reglas semánticas adicionales permanecen en dominio/codecs |
| Topic live_updates y marcador Dashboard | Outbox Match State, routing score.{matchId}, consumidor Dashboard, inbox/snapshot durable | E2E histórico y pruebas Dashboard | PASS de entrega/actualización observada; no certifica todas las ventanas de confirmación incierta |
| Fanout live_alerts | Outbox de alertas independiente y Notification con cola propia | E2E: alertas GOAL, VAR_CHECK y GOAL_ANNULLED, historial REST y PostgreSQL | PASS de difusión observada |
| Work Queue betting_commands y dos Workers | [Mapper académico](../services/betting-suspension-service/src/main/java/com/live_jgv/betting/academic/AcademicCommandMapper.java), outbox durable, consumidor académico y persistencia separada | E2E: GOAL/VAR_CHECK → SUSPEND_BETS; GOAL_ANNULLED/MATCH_CONTINUES → RESUME_BETS; cinco comandos procesados entre dos instancias, distribución 3/2 | PASS académico; no habilita ni reabre operaciones reales |
| Deduplicación y orden ORDER02 | Identidades durables; application_seq en cabecera y modo estricto Dashboard opt-in | E2E: duplicado Kafka y barrera PASS; tests offline de secuencia; [límites](integration/local-academic-v1/report.md) | PARCIAL: ensayo restringido, sin certificación global de reordenamiento arbitrario, replay automático ni múltiples autoridades |
| PostgreSQL, auditoría y JPA/Hibernate | [JpaArchiveStore](../services/historical-archiver/src/main/java/com/live_jgv/archiver/infrastructure/jpa/JpaArchiveStore.java); ArchiveStore como puerto, SportEvent como record; Flyway dueño del esquema, Hibernate validate | [Verificación JPA](../services/historical-archiver/docs/evidence/jpa/verification.json): 17/17 PG históricos; JPA_KAFKA: 4 eventos, 7 recibos, 2 duplicados, 1 conflicto, 0 pendientes, offset 7, reinicio y original intacto | PASS de laboratorios descritos; Fase 2B no ejecutada |
| SOLID, DIP e invariantes POO | [ArchiveConfiguration](../services/historical-archiver/src/main/java/com/live_jgv/archiver/infrastructure/ArchiveConfiguration.java) inyecta puerto y selecciona un adaptador; [MatchState](../services/match-state-service/src/main/java/com/live_jgv/matchstate/domain/model/MatchState.java) protege identidad, goles y anulaciones | Inspección estática y pruebas de dominio/adapter existentes | PASS de ejemplos concretos; no constituye prueba formal de los cinco principios en cada clase |
| JUnit 5, Mockito y TDD | Suites locales de dominio, servicios y adaptadores | CLONE: 885 Maven PASS; [RED académico](../services/betting-suspension-service/docs/evidence/academic-work-queue/red.txt) y [GREEN](../services/betting-suspension-service/docs/evidence/academic-work-queue/green-core.txt) conservados | PASS de herramientas y TDD documentado en ese desarrollo; PARCIAL para una afirmación histórica universal de TDD |
| Docker multietapa, Java 17, no root, recursos y réplicas | [Dockerfile](../Dockerfile), JAVA_OPTS controlados, límites CPU/RAM y replicas: 2 en Worker | DOCKER: siete builds --target runtime AMD64 PASS, Java 17.0.21+9, UID/GID 10001:10001, sin JAR locales; tests estáticos de recursos | PASS en AMD64; ARM nativo no certificado, requiere emulación. No se certifica consumo máximo observado de CPU/RAM |
| React, REST, STOMP, marcador, VAR y alertas | Dashboard frontend y backends; modos demostración/tiempo real separados | CLONE: React 165 tests, lint/build PASS, Node Dashboard 34 PASS y puente Notification–React PASS; E2E: REST/STOMP/React PASS | PASS dentro de ambas evidencias y sus revisiones |
| Recuperación y errores | Outboxes, recibos, deduplicación y reintentos existentes | JPA_KAFKA demuestra reinicio/offsets; tests locales de errores | PARCIAL: reentrega Rabbit real y todos los fallos inducidos no certificados |
| Seguridad del alcance simulado | Operaciones reales BLOCKED; acceptance OFF, autoridad disabled, sin habilitar grants/leases | E2E: cero operaciones, ACCEPTED, grants y leases; OFF independiente confirma baselines estables | PASS del RUN académico; fixtures atómicos de otro laboratorio no son operaciones del E2E |
| Cierre OFF verificable | Flags explícitos, comprobación CLI/HTTP y parada conservando recursos | OFF: tres muestras consecutivas en cero consumidores/conexiones AMQP, baselines conservados y once contenedores detenidos | PASS posterior independiente; E2E conserva su FAIL histórico de cierre |
| Cumplimiento literal integral | Requisitos oficiales confirmados por el usuario; lectura compatible VAR implementada, ZooKeeper distinto | Matriz y evidencias por alcance | PARCIAL: emisión literal VAR y diferencia KRaft; pendiente solo cotejo de líneas del archivo completo cuando sea accesible |

## Inventario exacto de 39 omisiones del clon limpio

Ninguna se presenta como aprobada. Se omitieron por ausencia de la variable de habilitación de su clase; no por fallos de compilación. Son 36 PostgreSQL, 2 Kafka y 1 RabbitMQ.

| Casos / clase | Guard e infraestructura | Evidencia previa y exclusión |
|---|---|---|
| 17 Archiver JpaPostgresIntegrationTest | ARCHIVER_JPA_POSTGRES_TESTS, PG y V1/V2 | 17/17 históricos en laboratorio; no reejecutados durante CLONE |
| 1 Archiver PreparedPostgresIntegrationTest | DEV3_RUN_POSTGRES_TESTS, PG upgrade V1→V2 | Ejecución histórica PG documentada; no nueva |
| 2 PreparedKafkaBrokerIntegrationTest, Archiver y Suspension | DEV3_RUN_ISOLATED_BROKER_TESTS, broker aislado | Consumo real histórico no acredita ejecución exacta de estos probes |
| 1 Suspension PreparedPostgresIntegrationTest | DEV3_RUN_POSTGRES_TESTS, PG | Fixture legacy emite lease: excluir de ensayo académico estándar; evidencia PG histórica no habilita su ejecución actual |
| 2 AcademicPreparedPostgresTest, Suspension y Worker | DEV3_RUN_ACADEMIC_POSTGRES_TESTS, PG/migraciones académicas | E2E acredita persistencia/entrega; no sustituye todos los asserts de rollback de estos métodos |
| 1 Worker PreparedPostgresIntegrationTest | DEV3_RUN_POSTGRES_TESTS, PG | Evidencia PG histórica; E2E acredita cero aceptaciones, no todo el método |
| 14 Worker AtomicPostgresConcurrencyTest | DEV3_RUN_ATOMIC_POSTGRES_TESTS, PG autoritativo y fixtures de grants/aceptación | [Laboratorio histórico](../services/betting-suspension-service/docs/postgres-real-certification.md); fuera de ensayo académico, no reejecutado ni certificado en dev actual |
| 1 Worker PreparedRabbitBrokerIntegrationTest | DEV3_RUN_ISOLATED_BROKER_TESTS, RabbitMQ | Comprueba exchange/cola pasivamente; Work Queue E2E no equivale a ejecutar este probe |

Tests externos excluidos mediante perfiles, como Notification *IT, no forman parte de estas 39 omisiones. No habilitar las 14 atómicas ni el legacy con lease para mejorar un contador.

## Once alertas npm: una raíz, sin actualización automática

Resultado conservado de npm audit del clon limpio: 11 altas, 0 críticas; npm audit --omit=dev: 0. Todas las rutas observadas pertenecen a herramientas de desarrollo. Un resultado cero de producción no elimina el riesgo del proceso Node de desarrollo/CI ni certifica seguridad completa del bundle.

| Paquetes contabilizados por audit | Ruta de dependencia |
|---|---|
| picomatch@4.0.7 (1) | Raíz: ReDoS al procesar extglobs maliciosos |
| fdir, tinyglobby (2) | tinyglobby → picomatch y tinyglobby → fdir → picomatch |
| vite, vitest (2) | vite/vitest → tinyglobby/picomatch; ambas herramientas son devDependencies |
| typescript-eslint, @typescript-eslint/eslint-plugin, @typescript-eslint/parser, @typescript-eslint/type-utils, @typescript-eslint/utils, @typescript-eslint/typescript-estree (6) | Convergen en typescript-estree → tinyglobby → picomatch |

El [aviso GHSA-fc5q-39xc-vjpf](https://github.com/advisories/GHSA-fc5q-39xc-vjpf), consultado para esta revisión, afecta hasta 4.0.7 y no publica versión corregida. No hay una actualización compatible demostrada que pueda prometerse aquí. Los downgrades mayores sugeridos por audit no se consideran corrección verificada.

Mitigación temporal: no exponer Vite/Vitest/linters como servicios públicos, no procesar patrones glob suministrados por usuarios no confiables, revisar cambios de configuración de glob en PR y mantener CI con permisos mínimos. DEV2 revisará el aviso y propondrá un PR independiente cuando exista remediación justificable, con pruebas, lint/build y audit comparativo. No ejecutar npm audit fix ni --force automáticamente.

## Registro de evidencias, revisiones y custodia

La evidencia versionada permanece intacta: [resultados JPA](../services/historical-archiver/docs/evidence/jpa/verification.json), [certificación PG anterior](../services/betting-suspension-service/docs/postgres-real-certification.md), [RED/GREEN académico](../services/betting-suspension-service/docs/academic-work-queue-implementation.md), [informe histórico de integración](integration/local-academic-v1/report.md) y [validación de clon/builds](CLEAN_CLONE_REVIEW.md). Los informes anteriores conservan su contexto temporal; sus pendientes no se convierten automáticamente en pendientes actuales.

Los originales de los siguientes resúmenes existen bajo custodia local fuera de Git. Se inspeccionaron sin ejecutarlos ni copiarlos: no son archivos requeridos para instalar el proyecto y no se publica configuración privada. El profesor debe recibir un paquete sanitizado separado si necesita revisar los originales. Los hashes identifican archivos, no sustituyen su entrega ni certifican por sí solos sus afirmaciones.

| ID | Revisión / resultado histórico | SHA-256 del resumen conservado |
|---|---|---|
| CLONE | dev 29ab42e; 885 Maven PASS, 39 omitidas; React165, Python84, Node34 PASS | cc937c93bfc64ce76b174a472aad5db4f3552dd8554954efa05126ad7c64f93a |
| DOCKER | d3b25d9; siete imágenes AMD64 runtime PASS; luego corrección documental 5c902d7 | b1084830976cf18393eff683f67bd639080489b9de4fd30331b8525c8de91e79 |
| E2E | c7c76bf, RUN e1248a11f4cc: funcional PASS; global FAIL_GLOBAL_CLOSURE_VERIFICATION conservado | 004b9aeca0e88c876acbddb5f3900434f5b006cc400f7a152eab7886c25ed2de |
| OFF | RUN e1248a11f4cc: cierre independiente posterior PASS, sin reescribir E2E | b26478954521c428f517b7ffd4c9f21757571dff9e9ceda92c17803ed85f76b9 |
| JPA_KAFKA | 37e2768, RUN 237b58a09ba5: Fase 2A PASS; Fase 2B no ejecutada | 2a00cba6a55bd1281d25c08a6f7d0ed93bac1091e48b6ae71a0800e54a186705 |

No se afirma que el E2E completo haya sido reejecutado sobre 29ab42e. Las certificaciones se complementan, no se suman como nuevas ejecuciones. Tampoco se extrapola el laboratorio atómico a apuestas operativas.

## Demostración propuesta: 5–7 minutos, sin repetir el E2E

Este guion usa capturas y evidencias existentes; no autoriza arrancar servicios. Si se requiere una sesión visual encendida, obtener autorización aparte y verificar modo OFF y recursos antes de abrirla. No ejecutar Producer ni harness como parte de este guion.

1. **0:00–1:00 — arquitectura:** mostrar Producer → Kafka → Match State; Topic live_updates → Dashboard; Fanout live_alerts → Notification; Kafka → Archiver/JPA y Suspension → Work Queue → dos Workers simulados.
2. **1:00–2:30 — marcador/VAR:** mostrar capturas y REST/STOMP/React del RUN: GOAL 1-0 CLEAR, VAR_CHECK 1-0 IN_PROGRESS, GOAL_ANNULLED 0-0 CLEAR con referencia al GOAL, MATCH_CONTINUES 0-0. Identificar datos reales persistidos frente a modo demo con fixtures.
3. **2:30–3:30 — mensajería:** mostrar tres alertas Fanout, cinco comandos académicos y cinco entregas distribuidas 3/2 entre Workers; distinguir SIMULATED_RESUMED de abrir un mercado real. No afirmar reentrega Rabbit: no fue ejecutada.
4. **3:30–4:45 — persistencia y VAR compatible:** mostrar los resultados separados JPA_KAFKA (siete offsets y reinicio) y VAR_JPA_KAFKA (cuatro offsets: anulación nueva, replay histórico y replay nuevo). Destacar 2 eventos, 4 recibos, 2 duplicados, offset confirmado 4 y bytes originales intactos. Son laboratorios distintos; no ejecutar SQL, migraciones ni publicaciones durante la exposición.
5. **4:45–5:45 — calidad/reproducción:** mostrar resumen CLONE, omisiones intencionales, RED/GREEN disponible y siete builds runtime AMD64. No convertir mocks en certificación de transacciones reales.
6. **5:45–7:00 — cierre y límites:** mostrar cero ACCEPTED/grants/leases, FAIL histórico de cierre y PASS OFF independiente con tres muestras cero. Explicar KRaft, nombre VAR, ORDER02 abierto, PLAINTEXT de laboratorio y alertas de desarrollo.

Material a preparar por el equipo: diagrama, capturas React con partido y VAR, respuestas REST/STOMP, alertas, auditoría de comandos con identidad de ambas instancias, resúmenes de PG/JPA/Kafka, reportes de tests/builds y muestras OFF CLI/HTTP. Revisar que el paquete no contenga credenciales, env privados, datos ajenos o logs completos sin sanitizar. No inventar capturas si un original no está disponible.

## Decisiones de aceptación y responsables

- Profesor y DEV1: resolver la diferencia KRaft/ZooKeeper. Equipo: revisar esta compatibilidad VAR autorizada y acordar cuándo cambiar la emisión a annuls_event_id, después de desplegar todos los lectores compatibles. No se exige dispensa para implementar el nombre oficial; mantener indefinidamente solo el nombre anterior sí requiere aceptar esa excepción.
- Equipo y profesor: aceptar el alcance V1 controlado y las exclusiones explícitas (ORDER02 global, fallos inducidos no certificados, apuestas reales/autoridad/leases). DEV1/DEV2 mantienen el seguimiento del orden; DEV3 preserva las guardas de operaciones.
- DEV2: registrar mitigación temporal npm; no declarar las 11 alertas corregidas.
- DEV3: custodiar originales y preparar paquete sanitizado si se aprueba compartirlo, conservando FAIL y PASS posteriores como resultados distintos.
- Equipo: aprobar este documento por PR, fijar SHA final de entrega y revisar el paquete académico. No hace falta repetir el E2E o builds para aprobar solo esta consolidación documental.

**Dictamen propuesto: compatibilidad VAR aprobada localmente y validada con Kafka/PostgreSQL reales para Archiver JPA; no certifica otro E2E ni todos los lectores desplegados. La emisión anterior se conserva por instrucción del equipo. El cumplimiento literal sigue PARCIAL hasta la transición de emisión y aceptación del profesor sobre ZooKeeper/KRaft.** Este documento no realiza ese cierre por su cuenta.


## Compatibilidad VAR autorizada: lectores primero, emisión sin cambios

Solo GOAL_ANNULLED puede usar annuls_event_id. En Producer REST, Match State, Archiver y Suspension se normaliza al referenceEventId de dominio. Si ambos campos son no nulos deben coincidir exactamente, sin trim ni selección del último valor. Null se trata como ausencia; una anulación sigue necesitando al menos una referencia textual no vacía. Tipos incorrectos, blancos y propiedades JSON repetidas se rechazan. El alias no modifica semánticas VAR_CHECK/MATCH_CONTINUES ni introduce VAR V2.

Los JSON Schema permiten una referencia válida en cualquiera de los dos campos y mantienen additionalProperties=false. JSON Schema no compara dinámicamente dos valores hermanos: la igualdad obligatoria la verifican los adaptadores Java. El descriptor del Producer y el schema compartido se mantienen idénticos. Las fixtures históricas permanecen intactas.

En Archiver/Suspension la whitelist de entrada acepta el alias, pero la representación canónica conserva exactamente las siete claves históricas y reference_event_id normalizado. Nunca se agrega annuls_event_id a canonical_payload. Esto permite deduplicar formatos equivalentes sin reescribir los registros existentes. Los bytes originales permanecen en el evento archivado y en cada recibo de transporte; cambiar bytes en la misma posición Kafka sigue siendo un error de identidad de origen, no un duplicado permitido. Un nuevo offset sí puede llevar el formato equivalente. Match State compara los eventos normalizados y mantiene sus invariantes de gol existente, partido, equipo y anulación única.

El Producer continúa serializando reference_event_id, tanto en Kafka como en la respuesta REST; no se cambia ScoreUpdate, LiveAlert, Work Queue, tablas, migraciones o topología. Desplegar primero los lectores compatibles de los tres consumidores deportivos y el Producer REST. Solo después de verificar el despliegue se podrá autorizar la emisión oficial de annuls_event_id. La prueba local de todos los lectores no certifica que el entorno desplegado ya los esté usando. Los lectores anteriores estrictos no aceptarían el campo nuevo.

Validación local de esta corrección: siete Maven verify PASS; 954 pruebas ejecutadas aprobadas, 39 omitidas, cero fallos y errores. Producer 93/0 omitidas; Match State 145/0; Archiver 115/19; Suspension 176/3; Worker 142/17; Dashboard 166/0; Notification 117/0. Son 69 pruebas adicionales frente a la base local anterior. Los tests VarFieldCompatibilityTest cubren ambos nombres, ambos iguales/en orden inverso, contradicciones, null/blanco/tipos inválidos, duplicación JSON, continuidad sin alias y replay. Pruebas del adaptador JPA y Suspension usan el decoder real con dobles de persistencia para verificar DUPLICATE, ausencia de nuevas transiciones/comandos, bytes originales y conflictos reales. No certifican semántica PostgreSQL; las 39 externas permanecen omitidas. Las pruebas de dominio preservan rechazo de gol inexistente y de segunda anulación con otro ID.

La implementación del PR #44 se verificó sin infraestructura. La validación real posterior de Archiver JPA se registra abajo como ejecución separada; no hubo un E2E nuevo ni builds Docker nuevos. Los resultados anteriores se conservan sin convertirlos en evidencia de otra revisión. El texto completo de 129 líneas se cotejará cuando el usuario lo facilite, según su instrucción; no bloquea el PR de compatibilidad solicitado.


## Validación real VAR_JPA_KAFKA: cierre acotado

Ejecución autorizada del RUN `f835870c8e96`, proyecto `live-jvg-var-compat-f835870c8e96`, SHA `a50ba417f960260624c1313b2b6321033bda100a`. Resultado **PASS**, registrado el 18 de noviembre de 2026 a las 00:57:34 America/Bogota (05:57:34 UTC). Se utilizaron PostgreSQL 17 y Kafka 3.9.1 nuevos y exclusivos, sin reutilizar los laboratorios anteriores. Archiver arrancó con JPA activo, Hibernate validate, Flyway OFF y consumidor inicialmente OFF, tras aplicar V1/V2 originales en la base nueva.

| Offset | Evento / formato | Recibo observado |
|---:|---|---|
| 0 | GOAL | STORED |
| 1 | GOAL_ANNULLED con annuls_event_id | STORED |
| 2 | Mismo evento con reference_event_id | DUPLICATE |
| 3 | Replay del formato nuevo | DUPLICATE |

Conteos comprobados: **2 eventos archivados, 4 recibos, 2 DUPLICATE, 0 conflictos, 0 pendientes; offset confirmado 4**. Cada recibo conserva exactamente su clave, payload y timestamp Kafka. El evento de anulación mantiene los bytes originales con annuls_event_id, mientras canonical_payload usa únicamente la referencia histórica normalizada reference_event_id. El replay no sobrescribe el original ni crea otro evento.

Al finalizar se detuvo el proceso Archiver y solo los dos contenedores del RUN; cuatro volúmenes exclusivos y su red quedaron conservados. Se verificó que las identidades y estados de los contenedores preexistentes no cambiaron. Evidencias privadas conservadas sin publicarlas: result.json, byte-evidence.json, recibos por offset, registros Kafka, migraciones, permisos, logs y resources-latest.json. No son dependencias para instalar el proyecto ni se copian en esta entrega documental.

SHA-256 del resumen result.json: `9b662e793045114e8df433a47c27f5cbc46aed4585fd3d22dc0e50ac7c9fb011`.
SHA-256 de byte-evidence.json: `b63dd76675929e519b5066b6db4ff1d75839c2d191712eb457c2035c78eed466`.
La identificación por hash no sustituye la revisión del original bajo custodia; no modifica los archivos del ZIP académico preparado anteriormente ni afirma que ya estén incluidos en él.

**Alcance:** confirma la lectura/normalización, deduplicación y preservación de bytes de Archiver JPA con Kafka/PostgreSQL reales. **Límites:** no prueba otro E2E de marcador, Match State, Suspension, RabbitMQ, REST/STOMP/React ni reentrega Rabbit; no hubo reinicio adicional del Archiver en este RUN ni fallos inducidos. No cambia la emisión del Producer, la topología KRaft, los resultados históricos ni las 39 omisiones de las suites locales. El PASS funcional histórico, su FAIL de cierre y el PASS OFF posterior permanecen independientes.

## Resumen de entrega académica

- Base documental: dev a50ba417; PR #44 integrado y CI posterior 10/10 PASS según la comprobación anterior, sin reejecutarlo en este cierre.
- Implementación VAR: 954 pruebas Maven locales aprobadas, 39 omitidas, cero fallos/errores; validación real adicional VAR_JPA_KAFKA PASS dentro del alcance anterior. No sumar sus cuatro registros como pruebas JUnit.
- Demostración: seguir el guion de 5–7 minutos de este documento usando evidencia existente; mostrar por separado marcador/alertas/Workers históricos y el nuevo resultado de archivo VAR. No arrancar infraestructura ni repetir el harness para presentar las evidencias.
- Pendientes concretos: aceptación explícita del profesor de KRaft frente a ZooKeeper, transición futura de emisión VAR solo tras verificar lectores desplegados y cotejo del texto de 129 líneas cuando se facilite. ORDER02 global y apuestas reales siguen fuera de lo certificado; las 11 alertas npm de desarrollo conservan su mitigación temporal.
