# Paso 5 — Preparación funcional y entrega DEV 3

2026-11-16. Rama `integration/Valeria-mvp-dev`, HEAD `c1df3f7`. Trabajo previo sin commit conservado. Resultado: **preparado para PR con restricciones; E2E no activado ni certificado**.

## Resultado funcional

No se encontró un defecto nuevo ni una capacidad independiente faltante respecto del alcance solicitado. No se reimplementaron servicios, añadieron DTO/destinos hipotéticos o pruebas redundantes. Cambios de este paso: este informe, `evidence/step-5-local-results.json` y [step-5-pr-files.txt](https://github.com/sebastianUCC2024/Live-JVG/blob/29fc8b041f22ec1f05cea5c9a200ceba47f1fb0c/services/betting-suspension-service/docs/step-5-pr-files.txt). Todo el código de pasos anteriores se conserva sin cambios.

| Servicio | Comportamiento existente comprobado en código/pruebas |
|---|---|
| Historical Archiver | Identidad `(match_id,event_id)`, recibo por posición Kafka, contenido original, conflicto sin sobrescritura, cuarentena durable, commit antes del retorno al listener. Duplicados/reintentos no crean otro evento. Archivo conserva hechos fuera de orden y timestamps originales; no proyecta ni reabre mercados |
| Betting Suspension | GOAL/VAR suspenden con bloqueos independientes; múltiples revisiones; cierre interno V2 dirigido y separado de V1; evidencia/versión/última continuación válidas; sin cierre automático desde CLEAR o mensajes ambiguos. Estado/ledger/cierre/outbox en la misma transacción; pendiente se recupera desde DB |
| Betting Worker | Entrada interna de dominio y listener opt-in, identidades command/operation estables, deduplicación/conflictos, autorización/aceptación/evidencia/inbox en la misma transacción. UNKNOWN/SUSPENDED/OPEN sin permiso bloquean. Rollback ante fallos; no hay ACCEPTED por lectura aislada ni segunda autoridad |

Se revisaron concretamente JdbcArchiveStore, Kafka container/listeners, JdbcSuspensionStore/MarketState, OutboxDispatcher/RabbitSuspensionPublisher, WorkerRabbitListener/Retry/StorageReadiness y sus pruebas existentes, sin repetir una auditoría general.

## Estado de adaptadores y límites

- Kafka: ByteArrayDeserializer, clave match_id validada, auto-commit false, ack RECORD y commits síncronos; retorno después del almacenamiento durable. Error PostgreSQL propaga; cuatro entregas y pausa operativa tras presupuesto agotado, sin descarte silencioso. No se afirma disponibilidad automática: requiere revisión/reanudación.
- Outbox: orden por partido, selección con SKIP LOCKED, payload/message_id persistidos, publicación mandatory/persistent con confirms/returns. Fallo o confirmación incierta revierte marcador de publicación; al retomar recupera el pendiente con la misma identidad. Reentrega es posible, no exactly-once entre DB y Rabbit.
- Worker Rabbit: cola/exchange explícitos, AUTO ack tras transacción, input inválido auditado, cuatro intentos y stop/requeue. La identidad de negocio no depende de delivery_tag. Tras reinicio usa inbox/operación persistidos, no cache.
- Variables de conexión y perfiles dev3-integration ya existen. Topic conocido `match_events`; no se inventaron exchange/routing/colas de apuestas. Destinos provisionales deben aprobarse antes de activar. Consumer/publication/acceptance/Flyway siguen false y modo atómico disabled.
- VAR V2 permanece **solo interno**. Se toma la confirmación de compatibilidad de DEV 2 reportada por el usuario; no equivale a congelación del contrato ni autorización E2E. No se cambió GOAL_ANNULLED ni se conectó cierre V2 al listener.
- LeaseSourceVerifier sigue denegando por defecto; el emisor no puede confiar solo en un string checkpoint. No existe fuente autoritativa aprobada ni emisor automático conectado. Lease SQL validado bajo lock no es una firma ni prueba de catch-up Kafka. No se inventó ninguna autoridad.

## Validación antes/después

No hay cambios de código en este paso, por lo que no se añadieron tests que dupliquen los existentes. Se ejecutaron de nuevo **mvn test y mvn package en cada POM individual**, Java 17.0.20.1 explícito y caché offline; sin gates PostgreSQL o brokers activados.

| Servicio | Exitosas antes | Exitosas después | Omitidas | Fallos | Errores |
|---|---:|---:|---:|---:|---:|
| historical-archiver | 76 | 76 | 1 | 0 | 0 |
| betting-suspension-service | 130 | 130 | 1 | 0 | 0 |
| betting-worker | 103 | 103 | 15 | 0 | 0 |
| Total | **309** | **309** | **17** | **0** | **0** |

Package repite casos, no aumenta el total. Las 17 omisiones del build predeterminado corresponden a las pruebas PostgreSQL ejecutadas explícitamente en la certificación anterior. No se volvieron a ejecutar en este paso. Kafka/Rabbit reales: cero pruebas nuevas; E2E sigue sin certificación. Las comprobaciones con mocks del publisher/listeners no se presentan como evidencia de brokers.

Cobertura ya disponible: duplicados y colisiones, eventos fuera de orden, múltiples VAR/cierres parciales, caída de publicación y rollback, recuperación con nueva instancia, comandos concurrentes, permiso denegado y lease no verificable. Se conservó la evidencia de reinicio físico PostgreSQL sin atribuirla a una nueva ejecución.

## Evidencia PostgreSQL lista para DEV 1

- Informe: `postgres-real-certification.md`.
- Resultados/casos/tiempos/comandos: `evidence/postgres-certification/results.json`.
- Logs públicos: `archiver-real-maven.txt`, `control-real-maven.txt`, `worker-real-maven.txt`, `atomic-real-maven.txt` en ese directorio.
- Catálogos/roles/constraints y reinicio: `final-after-restart.json`, `roles.json`, `restart-result.json`, `final-invariants.json`.

Se verificaron hashes SHA-256 de los cuatro logs contra results.json, 17 casos reales sin fallos/errores/skips, 40 tablas comparadas tras reinicio y cero ACCEPTED sin grant/grants sin ACCEPTED. Todos esos archivos y el informe mantienen sus hashes anteriores a este paso. Se revisó que no contienen los valores privados de credenciales ni firmas comunes de secretos. No se incluyen private.json, postgres.env, dumps, target ni binarios. No se enviaron mensajes a DEV 1 ni se publicaron archivos externamente.

## Lista futura de PR y estado Git

[step-5-pr-files.txt](https://github.com/sebastianUCC2024/Live-JVG/blob/29fc8b041f22ec1f05cea5c9a200ceba47f1fb0c/services/betting-suspension-service/docs/step-5-pr-files.txt) conserva la selección histórica de archivos que estaban **pendientes respecto de HEAD en aquel paso** de los tres módulos DEV 3, incluidos los cambios previos, certificación y este cierre. Excluye archivos ya comprometidos sin modificaciones, módulos ajenos y artefactos ignorados/generados. Era un inventario de entrega, no staging; se retiró del árbol actual tras la entrega. No usarlo para staging del estado vigente.

`git diff --check` satisfactorio. Ningún archivo previo fue sobrescrito, eliminado o modificado por este paso. Sin operaciones de integración Git, staging, commits o push; sin comandos Docker ni modificaciones de recursos. El PostgreSQL de laboratorio y sus evidencias se conservaron.

## Dependencias exactas para E2E

1. DEV 1: publicar/congelar contratos VAR V2 compatibles con V1, discriminador/mapeo y representación inequívoca de gol+revisión en GOAL_ANNULLED; confirmar configuración de brokers/Compose de integración, grupos, topics/retención y recursos aislados. Contratos compartidos siguen con cero bytes.
2. DEV 2: completar ProducerGateway y validar el flujo definitivo Dashboard/Notification con múltiples revisiones y cierres dirigidos. Su confirmación reportada de event_id de VAR_CHECK, referencia dirigida y CLEAR agregado sin permiso se mantiene como base de compatibilidad; no requiere agregar campos de DEV 3 por suposición.
3. Equipo: aprobar contratos/productor/topología Rabbit de apuestas, política de cierre de cada bloqueo GOAL/VAR, autoridad de frescura y verificador/emisor de leases. La autorización SQL no reemplaza esa definición.
4. Autorizar y ejecutar E2E con brokers reales: eventos, cuarentena, confirmación incierta/reentrega, recuperación de procesos y publicación pendiente. Adoptar owners/permisos/configuración común control+worker y plan de reconciliación de históricos antes de habilitar aceptación.

**Código preparado para PR**, con estas restricciones visibles; no se declara DEV 3 habilitado para uso E2E. No hay una corrección interna comprobada pendiente que pueda completarse independientemente de los acuerdos anteriores.
