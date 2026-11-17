# Revisión de las 22 pruebas Java omitidas

Clasificación comprobada contra los XML Surefire y el código. No se habilitaron variables de integración ni se
cambiaron exclusiones para conseguir verde. Las 22 siguen omitidas: 19 PostgreSQL, 2 Kafka, 1 RabbitMQ.
Ninguna puede certificar su objetivo original sin infraestructura; sustituirla por mocks/H2 no cerraría esa evidencia.

| Módulo | Clase / método | Recurso necesario | Línea fuente | Estado |
|---|---|---|---:|---|
| historical-archiver | PreparedPostgresIntegrationTest.v1UpgradePreservesEvidenceAndV2SupportsMatchScopedIdentityAndRecovery | PostgreSQL | [17](../../../services/historical-archiver/src/test/java/com/live_jgv/archiver/integration/PreparedPostgresIntegrationTest.java#L17) | OMITIDA, requiere autorización |
| historical-archiver | PreparedKafkaBrokerIntegrationTest.approvedIsolatedTopicExistsWithoutCreatingItOrCommittingOffsets | Kafka | [13](../../../services/historical-archiver/src/test/java/com/live_jgv/archiver/integration/PreparedKafkaBrokerIntegrationTest.java#L13) | OMITIDA, requiere autorización |
| betting-suspension-service | AcademicPreparedPostgresTest.academicPersistenceRestartDedupAndRollback | PostgreSQL | [8](../../../services/betting-suspension-service/src/test/java/com/live_jgv/betting/integration/AcademicPreparedPostgresTest.java#L8) | OMITIDA, requiere autorización |
| betting-suspension-service | PreparedPostgresIntegrationTest.stateReviewClosureDedupAndOutboxSurviveNewAdapter | PostgreSQL | [18](../../../services/betting-suspension-service/src/test/java/com/live_jgv/betting/integration/PreparedPostgresIntegrationTest.java#L18) | OMITIDA, requiere autorización |
| betting-suspension-service | PreparedKafkaBrokerIntegrationTest.approvedIsolatedTopicExistsWithoutCreatingItOrCommittingOffsets | Kafka | [13](../../../services/betting-suspension-service/src/test/java/com/live_jgv/betting/integration/PreparedKafkaBrokerIntegrationTest.java#L13) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.deadlockVictimCanRetryInANewTransaction | PostgreSQL | [190](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L190) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.lostDatabaseConnectionAfterGrantCannotCommitAcceptance | PostgreSQL | [21](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L21) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.unknownAndExpiredOpenCannotAccept | PostgreSQL | [143](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L143) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.unavailableAuthorityCannotLeaveCommittedOperation | PostgreSQL | [153](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L153) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.simultaneousIdenticalCommandsCommitOneOperationAndOneGrant | PostgreSQL | [49](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L49) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.directAcceptedWithoutCurrentTransactionGrantFailsAtCommit | PostgreSQL | [185](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L185) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.leaseExpiringAfterGrantRollsBackAtDeferredCommitCheck | PostgreSQL | [166](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L166) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.workerCannotWriteAuthorityOrForgeGrantDirectly | PostgreSQL | [39](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L39) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.rollbackAfterAuthorizationRemovesOperationAndGrantTogether | PostgreSQL | [160](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L160) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.grantCannotCommitSeparatelyFromItsAcceptance | PostgreSQL | [175](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L175) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.versionMismatchAndRestartCannotReuseOldPermission | PostgreSQL | [148](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L148) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.twoConcurrentOperationsSerializeAndDuplicatesRemainUnique | PostgreSQL | [137](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L137) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.suspensionBeforeAuthorizationBlocksAfterWaitingForItsCommit | PostgreSQL | [120](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L120) | OMITIDA, requiere autorización |
| betting-worker | AtomicPostgresConcurrencyTest.authorizationBeforeSuspensionKeepsSourceLockUntilOperationCommit | PostgreSQL | [128](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AtomicPostgresConcurrencyTest.java#L128) | OMITIDA, requiere autorización |
| betting-worker | AcademicPreparedPostgresTest.academicPersistenceRestartDedupAndRollback | PostgreSQL | [8](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/AcademicPreparedPostgresTest.java#L8) | OMITIDA, requiere autorización |
| betting-worker | PreparedRabbitBrokerIntegrationTest.approvedIsolatedCommandDestinationExists | RabbitMQ | [11](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/PreparedRabbitBrokerIntegrationTest.java#L11) | OMITIDA, requiere autorización |
| betting-worker | PreparedPostgresIntegrationTest.blockedOperationsAndSuspensionFenceSurviveNewAdapterAndDuplicate | PostgreSQL | [20](../../../services/betting-worker/src/test/java/com/live_jgv/worker/integration/PreparedPostgresIntegrationTest.java#L20) | OMITIDA, requiere autorización |

## Separación por alcance autorizado futuro

- **14 AtomicPostgresConcurrencyTest:** excluidas del ensayo académico; pueden crear grants/leases/ACCEPTED de laboratorio.
- **1 exclusión adicional:** Betting Suspension PreparedPostgresIntegrationTest.stateReviewClosureDedupAndOutboxSurviveNewAdapter
  abre mercados con closeReview y llama JdbcOpenLeaseIssuer con un verificador sintético. NO ejecutar bajo autorización académica.
- **7 candidatas restantes:** Archiver PostgreSQL, Control Academic PostgreSQL, Worker Academic PostgreSQL,
  Worker legacy BLOCKED PostgreSQL, dos probes Kafka y un probe Rabbit. Solo tras aprobación de infraestructura aislada.
  El Worker legacy crea operaciones BLOCKED y evidencia OPEN simulada/obsoleta, no grants; usar una base distinta
  del E2E académico, cuyo criterio es cero operaciones. Las pruebas que añaden constraints de fallo también son descartables.

## Qué exige cada grupo

- PostgreSQL: versiones/migraciones, restricciones diferidas, locks, rollback, persistencia y roles reales.
  Las cinco pruebas PostgreSQL fuera de AtomicPostgresConcurrencyTest usan bases nuevas y Flyway; no son solo lecturas. Una de ellas también emite un lease (ver exclusión adicional).
- Las 14 AtomicPostgresConcurrencyTest requieren dos conexiones, autoridad transaccional y fixtures de
  laboratorio que pueden crear leases/grants/ACCEPTED. Su autorización debe ser SEPARADA del ensayo
  académico de cero operaciones/grants. No habilitar DEV3_RUN_ATOMIC_POSTGRES_TESTS para demostrar Work Queue.
- Kafka: las dos son probes read-only AdminClient de topics aislados; aun así necesitan broker real y autorización.
  Sus guards exigen topic dev3_it_*: no certifican automáticamente match_events del common-v1.
- RabbitMQ: el probe pasivo usa destino legacy dev3_it_* y exchange nombrado. No certifica la nueva cola
  betting_commands mediante exchange por defecto, ni dos consumidores ni ACK/durabilidad reales.

Las pruebas locales de guardas de aislamiento y configuración sí se ejecutaron en las suites anteriores.
No hay una omisión accidental que pueda transformarse honestamente en PASS offline.

## Notification: exclusiones adicionales, distintas de esas 22

Notification ejecuta 117 pruebas locales, 0 omitidas. Sus 7 NotificationPostgresIT y 6 NotificationRabbitIT
NO entran en el ciclo local: son *IT con tag external-integration y perfil notification-integration/Failsafe.
Son 13 métodos NO EJECUTADOS, no 13 PASS ni 13 skipped añadidos a los 22 reportados por Surefire.

La revisión encontró una expectativa obsoleta en NotificationPostgresIT: Flyway carga V1 y V2 pero el
test esperaba solo las tablas V1. Se corrigió el inventario y las versiones esperadas; un nuevo test local
lee los dos SQL como texto y verifica que la expectativa es coherente. No se ejecutó el test PostgreSQL
ni se certificó que la migración, sus ownerships o restricciones funcionen físicamente.
