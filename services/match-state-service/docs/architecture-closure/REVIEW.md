# DEV 1: implementación y solicitud de revisión

Base: 2e53fde, posterior al HEAD auditado 7eb2de4 (PR #21 y documentación).
Rama: dev1/architecture-closure. Se conservan las cinco eliminaciones staged
encontradas al inicio; no forman parte de esta entrega. Sin push ni merge.

## Implementado

VarReviews es un modelo interno inmutable de identidad y ciclo de vida. Permite
varias revisiones, cierre parcial/total, duplicados y cierre tardío sin afectar
otra revisión. Retiene identidades cerradas para impedir reaperturas por redelivery.
Rechaza referencias desconocidas sin mutar el estado. No decide correlación de
GOAL_ANNULLED, GOAL ni selección V1/V2. No es un consumidor ni una cuarentena:
el adaptador futuro deberá conservar y clasificar los rechazos.

MatchState, sus mensajes, persistencia, outbox y replay V1 conservan sus reglas.
La política externa V2 no se activa. Las tres pruebas nuevas verifican aislamiento,
inmutabilidad, cierre, identidad y reconstrucción del modelo interno.

## Contratos propuestos: aprobación explícita pendiente

Los doce JSON de contracts/ son cuatro schemas draft-07 V1 y un ejemplo
válido/inválido por schema. No modifican contracts/events ni constituyen publicación.
Describen el formato local observado, con campos explícitos; su obligatoriedad y
rechazo de campos adicionales requieren revisión de consumidores. El ejemplo inválido
de cada contrato omite un campo requerido. Validación estructural no prueba semántica.

MatchEvent: identidad event_id dentro del partido; timestamp es hora deportiva,
no criterio de reordenación. team_id obligatorio para GOAL; reference_event_id
obligatorio para GOAL_ANNULLED. V1 no contiene discriminador en el mensaje.
ScoreUpdate: identidad derivada de match_id/event_id; updated_at es hora de
procesamiento, marcador no negativo, referencia preservada. Orden autoritativo
actual: application_seq persistida, nunca timestamp/minute. La entrega es al menos
una vez; consumidores deben deduplicar y detectar conflictos de contenido.
BettingCommand: refleja el decoder privado actual, schema_version=1, command_id
y operation_id distintos conceptualmente, simulated_user_id y timestamp obligatorios.
No aporta evidencia de autorización. LiveAlert refleja el DTO REST local de DEV 2:
contract_version=1.0, alert_id UUID, referencia al evento origen y tiempos separados.
No se propone usarlo como nuevo mensaje Rabbit sin acuerdo.

Mensajes malformados no deben mutar estado ni producir salida; conservar payload,
metadatos y motivo en custodia durable antes de confirmar. Referencias inexistentes
son diferibles; conflictos de identidad son permanentes. Reintentos limitados ante
fallos transitorios, sin bucles de poison messages. Acordar estos comportamientos
para cada consumidor: los schemas por sí solos no los implementan.

### Decisiones V2 solicitadas a DEV 2 y DEV 3

Propuesta: VAR_CHECK.event_id identifica revisión; MATCH_CONTINUES.reference_event_id
identifica una única revisión. Cierre parcial conserva IN_PROGRESS, total CLEAR.
CLEAR nunca autoriza apuestas. Un cierre desconocido se custodia, uno tardío no
cierra revisiones posteriores; duplicado idéntico no produce salida nueva.

GOAL_ANNULLED requiere dos identidades inequívocas: gol y revisión. Elegir entre
un campo review_event_id nuevo o un cierre separado; no inferirlo por timestamp
ni reutilizar silenciosamente reference_event_id. Resolver anulación directa,
revisión general y dos revisiones sobre el mismo gol. La propuesta D1_4A previa
infería revisión única por gol: esta decisión NO está aprobada ni implementada.
Resolver separadamente el cierre de bloqueos GOAL de DEV 3: una corrección deportiva
no prueba frescura ni autorización del mercado.

Discriminación: aprobar versión explícita y política inmutable por partido nuevo;
ausencia de versión conserva V1. Nunca reinterpretar históricos V1. Persistencia
actual solo permite rules_version=1: adaptar selección de motor, migración aditiva,
outbox y rollback en una entrega posterior aprobada. No migrar partidos en curso.

DEV 2 debe aprobar formatos/obligatoriedad, fixtures del simulador, UI ante cierre
parcial, texto de Notification y recuperación de snapshots. DEV 3 debe aprobar
correlación, custodia/replay, puente con ReviewClosure, bloqueos GOAL y versionado.
Ambos deben registrar aprobación explícita antes de activar serialización V2.
Esta solicitud queda preparada aquí; no se han enviado mensajes ni recibido votos.

## Infraestructura y CI para revisión coordinada

Compose usa proyecto aislado live-jvg-dev1-isolated y volúmenes propios; se retiró
container_name fijo para permitir coexistencia. scripts/start-mvp.sh existe ahora,
exige contraseñas locales explícitas, valida configuración y luego inicia el MVP.
Construir primero los JAR mediante Maven. No ejecutar ese script en bases compartidas.
scripts/validate-integration.ps1 solo valida con contraseñas ficticias.

integration-prepared añade Archiver, Suspension, Worker y Notification reutilizando
Dockerfile. Consumers, publication, acceptance, authorization y migraciones DEV 3
están desactivados. No publica puertos nuevos; comunicación interna por red de proyecto.
Healthchecks comprueban liveness, no disponibilidad funcional. Dockerfile instala
curl, requerido también por el healthcheck previo de Dashboard.

Los tres servicios DEV 3 apuntan provisionalmente a la base local con sus schemas
distintos; esto NO aprueba ownership, roles ni permisos de producción. Flyway está
desactivado: no se promete readiness con schemas ausentes. Notification permanece
sin storage ni messaging; su esquema requiere operación explícita propia de DEV 2.
Orden pendiente: provisionar roles/bases aisladas, aplicar migraciones por propietario,
validar readiness, declarar topología Rabbit, habilitar consumidores por separado.
Ningún volumen DEV 3 se reutiliza ni se elimina. No se ejecutó up/down ni migraciones.

CI conserva Java/React/Node e incorpora los cuatro módulos a la matriz local.
Cambios preparados para revisión DEV 2/3 antes de merge. Tests PostgreSQL de DEV 3
ya tienen condiciones de entorno: sus skips no constituyen certificación externa.
No se añaden jobs externos vacíos o verdes mediante skips.

Separación de gates: local=mvn verify; PostgreSQL=perfil integration-postgres de
Match State y suites externas propias de cada dueño; brokers=integration-brokers;
E2E=entorno completo con evidencia. Los tres últimos necesitan infraestructura,
credenciales y aprobación coordinadas antes de configurar jobs que los ejecuten.

## Propuesta de autoridad y productor simulado (no activada)

DEV 3 conserva ownership de autorización. Una autoridad debe registrar evidencia
verificable por source/epoch/partición/checkpoint y versión de mercado, con emisión,
expiración corta, identidad única y auditoría. Hora sincronizada por sí sola no basta.
Invalidar ante reinicio, cambio de epoch, lag, pérdida de partición, discontinuidad,
revisión nueva o hueco de secuencia. Eventos atrasados no deben renovar evidencia:
exigir frontera verificable de offsets y estabilidad del checkpoint. Un Kafka
silencioso no demuestra frescura; si no hay evidencia, rechazar aceptación.

La decisión final debe comprobarse atómicamente con el registro de la operación,
con versión esperada y fencing de epoch; separar emisión de evidencia de su consumo.
Replay nunca renueva leases ni reutiliza autorizaciones. Deduplicar comandos y
operaciones, comprobar expiración en el servidor y revocar evidencia tras cambios.
El mecanismo existente de DEV 3 requiere revisión conjunta antes de cualquier lease real.

Productor propuesto: herramienta CLI de integración bajo ownership por acordar,
sin asignarlo a Dashboard. Default dry-run, usuarios simulados, reloj inyectable,
IDs estables en reintentos y manifiesto reproducible. Publicación Rabbit solo en
vhost aislado tras aprobación: exchange/routing/queue a acordar con DEV 3, mensajes
persistentes JSON, message_id=command_id, mandatory, publisher confirms, reintentos
limitados con mismo ID y DLQ/custodia. No declarar una topología de producción por
suposición. El comando no transporta una autorización confiable ni evita al worker.

## Restante para E2E real

1. DEV 2/3 aprueban contratos y discriminación V1/V2, GOAL_ANNULLED y cierres GOAL.
2. DEV 1 conecta motor aprobado, persistencia versionada, ingress/custodia y fixtures.
3. DEV 2 adapta simulador/UI/Notification según contratos aprobados.
4. DEV 3 aprueba base común, permisos, migraciones, topología y autoridad operativa.
5. Acordar propietario e implementar productor simulado sin bypass.
6. Construir imágenes y arrancar proyecto aislado; comprobar migraciones y readiness.
7. Ejecutar PostgreSQL, Kafka/Rabbit, duplicados, fallos de publicación, reinicio,
   replay, eventos atrasados y decisiones del worker sin aceptación no autorizada.
8. Ejecutar flujo completo, recoger IDs/offsets/filas/logs y certificar únicamente
   lo realmente observado. Esta entrega NO certifica E2E.

## Evidencia de validación local

Los seis comandos Maven verify terminaron BUILD SUCCESS con Java 17:

| Servicio | Tests reportados | Fallos/errores | Skips |
|---|---:|---:|---:|
| Producer | 78 | 0/0 | 0 |
| Match State | 103 | 0/0 | 0 |
| Archiver | 77 | 0/0 | 1 |
| Suspension | 131 | 0/0 | 1 |
| Worker | 118 | 0/0 | 15 |
| Notification | 103 | 0/0 | 0 |

Los 17 skips DEV 3 corresponden a gates externos existentes. Los tests externos
excluidos por perfiles/tags tampoco están certificados. Logs locales:
services/live-feed-producer/target/dev1-verify.log,
services/match-state-service/target/dev1-verify.log y tmp/dev1-*-verify.log.
La verificación de Match State incluye VarReviewsTest (3 casos) y replay V1.

scripts/validate-dev1-contracts.py valida cuatro schemas draft-07, cuatro ejemplos
válidos y cuatro inválidos; también parsea workflow y comprueba matriz de siete
servicios y conservación de jobs. Dependencias: fastjsonschema==2.22.3 y
ruamel.yaml==0.19.1 (instaladas temporalmente en tmp/dev1-validators).
Compose config --quiet pasó con integration-prepared y credenciales ficticias.
git diff --check pasó. No se construyeron imágenes ni ejecutaron pruebas externas.

CI incorpora además integration-static para repetir schemas/YAML/Compose. Los
workflows no se ejecutaron en GitHub; Dockerfile y arranque real quedan pendientes
de validación aislada. Los checks de liveness no prueban schemas ni topología.
