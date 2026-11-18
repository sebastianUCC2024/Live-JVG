# Live-JVG — aceptación académica V1

Fecha de consolidación: 18 de noviembre de 2026, America/Bogota.
Base inspeccionada: dev `29ab42e22c9042871de36a7b3799120f421e91b9` (merge del PR #40).
Este documento propone la aceptación del alcance académico demostrado; no aprueba contratos ni excepciones en nombre del profesor y no certifica producción.

## Fuente y estados

No se encontró el enunciado original entre los archivos versionados (incluidos documentos PDF/Word/ODT). Los requisitos de la matriz se reconstruyen exclusivamente de instrucciones del equipo y de la [matriz académica histórica](integration/local-academic-v1/report.md). **Todas las filas requieren cotejo literal con el documento oficial.** No se inventan citas, números de rúbrica ni requisitos faltantes.

PASS acredita únicamente el alcance indicado por su evidencia. PARCIAL indica límites técnicos o cobertura incompleta; PENDIENTE requiere decisión/evidencia; DIFERENCIA LITERAL identifica una divergencia terminológica o tecnológica reportada en las fuentes secundarias. Una ejecución histórica no es una ejecución nueva sobre el dev actual.

## Matriz de trazabilidad

| Requisito reconstruido, pendiente de cotejo oficial | Implementación | Prueba / evidencia existente | Estado y límite |
|---|---|---|---|
| Java 17 y microservicios | Siete módulos con POM independiente | Clon limpio CLONE: siete verify, 885 aprobadas, 39 omitidas, cero fallos/errores | PASS del ciclo local; no todos los servicios usan la misma versión Spring Boot |
| Kafka y publicación por partido | [KafkaMatchEventPublisher](../services/live-feed-producer/src/main/java/com/live_jgv/live_feed_producer/infrastructure/kafka/KafkaMatchEventPublisher.java): clave event.matchId(); topic match_events y grupos independientes | E2E histórico E2E; laboratorio JPA_KAFKA con siete posiciones y offset confirmado 7 | PASS del recorrido ensayado; orden Kafka por partición, no orden global |
| ZooKeeper | Compose declara Kafka 3.9.1 KRaft broker/controller, sin ZooKeeper | [Compose](../docker-compose.yml) y documentación del laboratorio | DIFERENCIA LITERAL si el original exige ZooKeeper; requiere aceptación del profesor |
| GOAL, VAR_CHECK, GOAL_ANNULLED, MATCH_CONTINUES y corrección de marcador | Dominio Match State y codecs; anulación referencia el gol original | [MatchStateTest](../services/match-state-service/src/test/java/com/live_jgv/matchstate/domain/model/MatchStateTest.java), E2E: 1-0 CLEAR → 1-0 IN_PROGRESS → 0-0 CLEAR → 0-0 CLEAR | PASS de secuencia V1 controlada |
| Campo de referencia VAR | Wire existente reference_event_id; no se introduce alias annuls_event_id | [Schema MatchEvent](../contracts/events/match-event.schema.json), fixtures y evidencia E2E | DIFERENCIA LITERAL reportada: fuentes secundarias atribuyen annuls_event_id al enunciado; cotejar y aceptar equivalencia antes de afirmar cumplimiento literal |
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
| Cumplimiento literal integral | Documento oficial no disponible en Git | Comparación secundaria únicamente | PENDIENTE: profesor/equipo deben cotejar y aprobar excepciones |

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
4. **3:30–4:30 — persistencia:** mostrar auditoría PostgreSQL y resumen JPA_KAFKA con siete offsets, duplicados, conflicto, original intacto y reinicio. No ejecutar SQL ni migraciones durante la exposición de evidencias.
5. **4:30–5:30 — calidad/reproducción:** mostrar resumen CLONE, omisiones intencionales, RED/GREEN disponible y siete builds runtime AMD64. No convertir mocks en certificación de transacciones reales.
6. **5:30–7:00 — cierre y límites:** mostrar cero ACCEPTED/grants/leases, FAIL histórico de cierre y PASS OFF independiente con tres muestras cero. Explicar KRaft, nombre VAR, ORDER02 abierto, PLAINTEXT de laboratorio y alertas de desarrollo.

Material a preparar por el equipo: diagrama, capturas React con partido y VAR, respuestas REST/STOMP, alertas, auditoría de comandos con identidad de ambas instancias, resúmenes de PG/JPA/Kafka, reportes de tests/builds y muestras OFF CLI/HTTP. Revisar que el paquete no contenga credenciales, env privados, datos ajenos o logs completos sin sanitizar. No inventar capturas si un original no está disponible.

## Decisiones de aceptación y responsables

- Profesor y DEV1: cotejar el original y aceptar o rechazar KRaft frente a ZooKeeper y reference_event_id frente a annuls_event_id. No cambiar contratos unilateralmente para ajustar nombres.
- Equipo y profesor: aceptar el alcance V1 controlado y las exclusiones explícitas (ORDER02 global, fallos inducidos no certificados, apuestas reales/autoridad/leases). DEV1/DEV2 mantienen el seguimiento del orden; DEV3 preserva las guardas de operaciones.
- DEV2: registrar mitigación temporal npm; no declarar las 11 alertas corregidas.
- DEV3: custodiar originales y preparar paquete sanitizado si se aprueba compartirlo, conservando FAIL y PASS posteriores como resultados distintos.
- Equipo: aprobar este documento por PR, fijar SHA final de entrega y revisar el paquete académico. No hace falta repetir el E2E o builds para aprobar solo esta consolidación documental.

**Dictamen propuesto: listo para aceptación académica con condiciones explícitas; sin bloqueos funcionales nuevos demostrados, pendiente cotejo literal y decisiones del profesor.** Este documento no realiza ese cierre por su cuenta.
