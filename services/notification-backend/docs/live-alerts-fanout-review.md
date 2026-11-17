# LiveAlert V1 fanout — implementación offline para revisión

Base 257662cc6fb5c300e01e5ad4ad967566dd60af88; rama
feature/dev2-live-alerts-fanout. No hay commit ni publicación de esta implementación.

## Flujo y contrato

Match State conserva ScoreUpdate V1 en live_updates/topic para Dashboard. En el
mismo commit de accepted_event y score_outbox añade alert_outbox cuando el nuevo
flag de generación está habilitado. No hay binding entre exchanges ni dos envíos
consecutivos sin custodia durable. El dispatcher de alertas publica explícitamente
en live_alerts/fanout durable, con routing key vacía y message_id=alert_id.

El contrato contracts/events/live-alert.schema.json es el nuevo evento AMQP, no
el DTO REST histórico. Campos requeridos: contract_version=1.0, alert_id UUID,
match_id, source_event_id, event_type, message, timestamp, reference_event_id y
snapshot ScoreUpdate V1. Snapshot es un record inmutable de strings, enteros,
enums e Instant. Envoltorio y snapshot deben coincidir en partido, evento, tipo,
tiempo y referencia. El schema expresa la estructura; ambos DTO Java validan la
coherencia entre campos. El contrato público ScoreUpdate y su código no cambian.

GOAL, VAR_CHECK y GOAL_ANNULLED generan alerta. MATCH_CONTINUES no produce alerta
visible, preservando la política previa. Una anulación conserva la referencia al
gol. No se inventan nombres, jugadores ni minutos. Timestamp representa updated_at
del snapshot de origen. REST created_at usa el timestamp de la alerta de origen;
received_at del inbox sigue siendo la recepción local.

## Custodia e independencia

V4 Match State prepara alert_outbox: payload y destino persistidos, UUID propio,
identidad única por partido/evento, orden application_seq y published independiente.
El UUID se genera al insertar y no se regenera en redelivery. No se backfillea historia.
Un error al insertar alerta revierte la transacción completa; con el flag OFF no se
consulta esa outbox y se conserva el flujo ScoreUpdate anterior.

El dispatcher separado mantiene orden por partido con FOR UPDATE/SKIP LOCKED y
barrera de pendientes anteriores. Solo marca entrega después de confirm correlacionado
positivo y sin retorno mandatory. Error, timeout, retorno o rollback conserva pendiente.
Un resultado incierto puede duplicar entregas Rabbit; mantiene ID y payload originales.
El fallo de alertas no modifica published de score_outbox. Un partido problemático
puede bloquear su propia secuencia; no se descartan sus alertas automáticamente.
La publicación espera confirms dentro de una transacción corta de outbox: validar
timeouts, locks y comportamiento del pool en pruebas PostgreSQL autorizadas futuras.

V2 Notification añade live_alert_inbox, con identidad única por alert_id y por
match_id/source_event_id. Se registra el envelope completo en la misma transacción
REQUIRES_NEW del inbox anterior, alerta y cursor. Redelivery idéntica no crea alerta;
cualquier diferencia en identidad/contenido se rechaza. UUID del productor y mensaje
se conservan en REST; marcador/VAR se leen del snapshot, sin consultas cruzadas.
El listener conserva reintentos limitados, ACK posterior al commit y transferencia
confirmada a DLQ. Si no hay custodia DLQ: NACK requeue y detención. No consume la DLQ.

## Configuración OFF y transición

Nuevos controles por defecto false:

- MATCH_STATE_ALERTS_ENABLED: generar filas durables para eventos nuevos.
- MATCH_STATE_ALERTS_PUBLICATION_ENABLED: crear dispatcher y declaración fanout.
- NOTIFICATION_LIVE_ALERTS_ENABLED: seleccionar contrato/topología nuevos.

NOTIFICATION_MESSAGING_ENABLED y NOTIFICATION_STORAGE_ENABLED siguen false por
defecto. Con messaging OFF no hay configuración Rabbit específica de Notification.
No activar el nuevo modo sin messaging/storage, migraciones y ACL aprobados. El
modo legacy topic permanece compatible para transición; no ejecutar ambos modos
sobre el mismo conjunto de eventos. La selección de protocolo no activa consumo.

La publicación de alertas exige generación y la publicación general habilitadas;
si están deshabilitadas, falla la configuración en lugar de confirmar ficticiamente.
El modo nuevo es durable y no se ofrece como flujo memory. El flujo ScoreUpdate
preexistente conserva sus defaults: estos flags solo controlan la integración nueva.

El allowlist se valida en el consumidor: fanout no filtra por partido en bindings.
AMQP exchange debe ser live_alerts y message_id debe coincidir con alert_id.
No se permite enviar directamente al consumidor un envelope incoherente.

Migrar primero Match State V4 y Notification V2, tras sus versiones previas, mediante
autorización separada. Los SQL no se ejecutaron. Al cambiar desde legacy, usar un
run nuevo: si existe inbox legacy para el evento, se rechaza la colisión y no se
duplica la alerta. Los registros históricos no se modifican ni se les asigna otra
identidad. Restauraciones parciales entre servicios siguen requiriendo política de
identidad y epochs del operador; esta implementación no promete resolverlas.

## ACL y common-v1

La propuesta common-v1 copiada en ESTE worktree se actualizó a catorce migraciones,
incluyendo las dos nuevas tablas. La versión anterior en su propio worktree sigue
intacta. Generador, pruebas, hashes y preflight reflejan el cambio de topic a fanout.

Publisher Match State: write live_alerts; configure solo si declara el exchange.
Notification dedicada notification_e2e_5c84548cf006: configure/read live_alerts;
configure/read/write su cola y DLX; configure/write DLQ, sin read DLQ ni write fanout.
No requiere topic permissions ni acceso live_updates para la nueva ruta. RabbitAdmin
explícito requiere permisos de declaración aun con spring.rabbitmq.dynamic=false.
Configure permite más que declarar: el operador debe revisar ese riesgo por recurso.

Runtime Match State: INSERT/SELECT/UPDATE alert_outbox. Runtime Notification:
INSERT/SELECT live_alert_inbox y los grants previos. No se concedieron privilegios;
los SQL y JSON son propuestas. No se modificó Compose: deben coordinarse con DEV 1
los nuevos flags OFF, artefactos, versión de migraciones y futuros destinos.

## Evidencia y límites

Maven verify offline: Match State 126/126; Notification 116/116, BUILD SUCCESS.
Regresión Dashboard: 159/159 y BUILD SUCCESS. React sin cambios: 143/143 Vitest.
Common-v1: 25/25 pruebas Python y render determinista correcto. git diff --check
sin errores. La validación de guards TypeScript sobre el JSON MVC también pasó.
Logs: target/live-alerts-final-verify.log de cada módulo. Pruebas nuevas cubren
atomicidad, rollback, pendiente/reintento, GOAL, anulación, identidad duplicada y
conflicto, topología durable y ausencia de beans/declaraciones nuevos cuando OFF.
La prueba ProducerLiveAlertCompatibilityTest compila los records reales de Match
State y valida sus bytes JSON en el codec Notification; no inicia ese servicio.

LiveAlertRestCompatibilityTest usa MVC real con un puerto de almacenamiento doble;
genera target/live-alert-rest-fixture.json. El script
scripts/verify-react-alert-contract.cjs valida esa respuesta con los guards TypeScript
reales e intactos del frontend, usando dependencias ya existentes indicadas por
JVG_FRONTEND_NODE_MODULES. No instala herramientas ni contacta servicios.

Las pruebas JDBC son con dobles: no prueban restricciones PostgreSQL, locks reales,
permisos, Flyway, conectividad, Rabbit confirms efectivos, persistencia tras caída
ni E2E. La declaración fanout no prueba entrega a cada suscriptor. Los confirms y
mandatory requieren pruebas reales futuras con todas las colas aprobadas presentes.
DLQ puede recibir duplicados si se pierde ACK después de transferir; no es exactly-once.

Coordinar con DEV 1: JdbcMatchStateStore, DurableStorageConfiguration, las clases
nuevas de publicación y V4. Revisar conjuntamente el schema compartido LiveAlert.
DEV 3 no tiene archivos modificados; SUSPEND_BETS/RESUME_BETS continúa fuera de alcance.
No se activó aceptación, autoridad, leases ni consumidores externos.

DICTAMEN: LISTO PARA REVISIÓN OFFLINE; no autorizado para despliegue ni E2E.

## Inventario final de archivos para revisi?n

```text
contracts/events/live-alert.schema.json
services/match-state-service/src/main/java/com/live_jgv/matchstate/domain/model/LiveAlertV1.java
services/match-state-service/src/main/java/com/live_jgv/matchstate/infrastructure/config/DurableStorageConfiguration.java
services/match-state-service/src/main/java/com/live_jgv/matchstate/infrastructure/config/LiveAlertPublicationConfiguration.java
services/match-state-service/src/main/java/com/live_jgv/matchstate/infrastructure/jdbc/JdbcMatchStateStore.java
services/match-state-service/src/main/java/com/live_jgv/matchstate/infrastructure/rabbit/LiveAlertOutboxDispatcher.java
services/match-state-service/src/main/resources/application.properties
services/match-state-service/src/main/resources/db/migration/V4__live_alert_outbox.sql
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/rabbit/LiveAlertOutboxTest.java
services/notification-backend/docs/common-v1-proposal/10-provision.proposed.sql
services/notification-backend/docs/common-v1-proposal/20-cross-owner.proposed.sql
services/notification-backend/docs/common-v1-proposal/30-passive-grants.proposed.sql
services/notification-backend/docs/common-v1-proposal/40-fixtures.proposed.sql
services/notification-backend/docs/common-v1-proposal/50-select-preflight.proposed.sql
services/notification-backend/docs/common-v1-proposal/60-future-runtime-dml.proposed.sql
services/notification-backend/docs/common-v1-proposal/README.md
services/notification-backend/docs/common-v1-proposal/SHA256SUMS
services/notification-backend/docs/common-v1-proposal/notification-rabbit.proposed.json
services/notification-backend/docs/common-v1-proposal/plan.proposed.json
services/notification-backend/docs/common-v1-proposal/prepare_common_v1.py
services/notification-backend/docs/common-v1-proposal/test_prepare_common_v1.py
services/notification-backend/docs/live-alerts-fanout-review.md
services/notification-backend/scripts/verify-react-alert-contract.cjs
services/notification-backend/src/main/java/com/live_jgv/notification/application/NotificationStore.java
services/notification-backend/src/main/java/com/live_jgv/notification/application/ProcessScoreUpdateService.java
services/notification-backend/src/main/java/com/live_jgv/notification/domain/LiveAlertV1.java
services/notification-backend/src/main/java/com/live_jgv/notification/infrastructure/amqp/ScoreUpdateListener.java
services/notification-backend/src/main/java/com/live_jgv/notification/infrastructure/config/RabbitConfiguration.java
services/notification-backend/src/main/java/com/live_jgv/notification/infrastructure/jdbc/JdbcNotificationStore.java
services/notification-backend/src/main/java/com/live_jgv/notification/infrastructure/json/LiveAlertCodec.java
services/notification-backend/src/main/resources/application.properties
services/notification-backend/src/main/resources/db/migration/V2__live_alert_identity.sql
services/notification-backend/src/test/java/com/live_jgv/notification/LiveAlertContractTest.java
services/notification-backend/src/test/java/com/live_jgv/notification/ProducerLiveAlertCompatibilityTest.java
services/notification-backend/src/test/java/com/live_jgv/notification/domain/LiveAlertV1Test.java
services/notification-backend/src/test/java/com/live_jgv/notification/infrastructure/jdbc/LiveAlertPersistenceTest.java
services/notification-backend/src/test/java/com/live_jgv/notification/infrastructure/web/LiveAlertRestCompatibilityTest.java
```
