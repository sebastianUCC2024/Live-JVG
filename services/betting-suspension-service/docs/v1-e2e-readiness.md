# DEV 3 — Gate previo al ensayo E2E V1

Revisión: 2026-11-16 local / 2026-11-17 UTC. HEAD `3fb2a10e37e6a0ee820ccf2e8345e14c6c830dd8`.
Rama feature/Valeria-dev3-e2e-preparation; árbol inicial limpio. HEAD, origin/dev local y
refs/heads/dev en GitHub coinciden. PR26 MERGED a 2026-11-17T00:19:56Z, merge 3fb2a10.
PR26: 20 checks SUCCESS sobre 0a7306d (dos runs push/PR de diez jobs cada uno).
Commit actual 3fb2a10: diez checks, todos completed/success, incluido Dashboard.
No se hizo fetch, pull, merge, cambio de rama, commit, push ni operación Docker/SQL/broker.

## Checklist y respuestas para DEV 1/2

CONFIRMADO es evidencia técnica o restricción del usuario; no aprobación de contrato.

| Requisito | Estado real / respuesta |
|---|---|
| Aceptación OFF, authorization.mode disabled | CONFIRMADO en application.properties y perfiles; no se emiten leases. |
| Consumidores/publicación/Flyway automáticos OFF | CONFIRMADO; StorageReadiness exige schemas y permisos aun con transporte OFF. |
| CI actual | CONFIRMADO 10/10. Fallos Dashboard citados en E2E-RUNBOOK/TEAM-CONFIG son históricos, no bloqueo CI vigente. |
| Identidad de eventos | Archiver V2 y Suspension usan (match_id,event_id). Las referencias a Archiver global y solo V1 en docs DEV1 están desactualizadas. |
| VAR_CHECK.event_id como revisión | Preparado internamente; no aprobación wire V2. |
| ReviewClosure | Comando interno auditado, versión y evidencia vigente; V1_GOAL/V2_REVIEW, no V2_VAR_CHECK. Cerrar VAR no libera GOAL hold independiente. |
| GOAL_ANNULLED | V1 referencia GOAL; no inventar doble identidad/revisión V2. Pendiente contrato del equipo. |
| V1/V2 y replay | No reinterpretar histórico V1, no agregar discriminador unilateralmente a decoders estrictos. Deduplicación no implica aprobación semántica. |
| BettingCommand | Cuatro fixtures PR22 compatibles con decoder real; ownership y topología operativa pendientes. Harness produce comando sintético solamente. |
| Custodia | Inválidos se guardan antes de ACK; fallo de DB no debe confirmar. DLQ nueva no aprobada; Worker agota retry, detiene y reencola. |
| Autoridad/epoch/checkpoint | Epoch persistente de DB no equivale a source-id de Kafka; checkpoint opaco no acredita cobertura/lag/frescura. Verificador real pendiente, default deniega. |
| ACK y confirms | Kafka commit DB antes del retorno/offset; Rabbit ACK tras persistencia. Outbox confirm/mandatory/return, ventana incierta puede reentregar; requiere dedup durable. |
| Proyecto de ensayo autorizado | PENDIENTE. Ni PR fusionado ni aprobación anterior del laboratorio autorizan este entorno nuevo. |
| Runtime no superusuario | PENDIENTE comprobar en servidor nuevo; no usar live_jvg admin como login app. |
| Roles y migraciones | Generador offline preparado; control V1/V2 antes de worker V1/V2. Archiver V1/V2 independiente. No ejecutado. |
| Nueva certificación PG/brokers/E2E | NO EJECUTADA. 17/17 PG es evidencia histórica y no cubre estos grants nuevos. |

## Defecto propio corregido en preparación SQL

StorageReadiness de Suspension consulta authorization_lease; Worker consulta
operation_authorization. El generador omitía SELECT de runtime sobre esas tablas:
los servicios no arrancarían con esos grants mínimos aunque consumidores estuvieran OFF.
Se añaden únicamente grants SELECT y una prueba offline de regresión. No se conceden
INSERT/UPDATE/DELETE/TRUNCATE, ownership ni capacidades de lease a esos logins.
No se alteran migraciones históricas ni el runtime Java. Nueva prueba offline no certifica
ACL reales: verificar arranque y rechazo de escritura con los runtimes del entorno nuevo.

## Manifiesto y SQL preparados

Manifiesto local sin secretos: `/private/tmp/live-jvg-dev3-v1-readiness/environment-manifest.json`.
Proyecto propuesto: `live-jvg-e2e-5c84548cf006`. Run propuesto: `5c84548cf006`. approved_environment=false,
status PREPARED_ONLY_NOT_EXECUTED. SQL pre/post separado en el mismo directorio.
El generador no se conecta y abortaría aprovisionamiento si DB/roles nombrados ya existen.
Seis roles separados control/worker, owners NOLOGIN, migradores y runtime sin superusuario.
Passwords fuera de Git y del manifiesto. DBA debe instalar bajo owners y auditar funciones,
search_path, memberships, grants heredados, restricciones diferidas y epoch con esos roles.

Limitación explícita: SQL generado usa DB nueva dev3_it_5c84548cf006_authority. Harness fija
live_jvg en un proyecto NUEVO; no ejecutar ese SQL y luego asumir que satisface el harness.
Sebastián debe coordinar provisión en su nueva live_jvg o acordar extensión del harness.
No cambiar nombre de DB por sustitución ciega ni reutilizar una anterior. Archiver independiente
requiere además owner/migrador/runtime propios y V1/V2, no incluidos en los seis roles del generador.

## Topología temporal propuesta, NO aprobada

Ver nombres exactos en temporary_brokers del manifiesto: topic dev3_it_5c84548cf006_events,
grupos archive/control distintos, exchange commands, cola worker y routing por run.
Vhost propuesto dev3_it_5c84548cf006; host/puertos libres aún sin verificar (no se llamó Docker).
Key Kafka: match_id. Source IDs: identificador nuevo del mismo log físico, estable tras
restart, nunca local/1 para otro clúster. Epoch DB se lee después de migrar, no se inventa.

Harness V1 usa match_events y live_updates fijos, además de live_jvg. Son seguros SOLO
si pertenecen al nuevo proyecto aislado aprobado; los nombres temporales del manifiesto
sirven para pruebas DEV3 separadas, no son configuración directamente intercambiable con
ese harness. Worker exchange/queue/routing/vhost deben acordarse y provisionarse con binding,
permisos mínimos y cola durable. Publication de Suspension sigue OFF; probar su outbox real
necesita destino de mercado adicional aprobado, no confundirlo con comandos Worker.
Los probes pasivos no verifican bindings, confirms, ACK ni entrega de payload.

## Casos para ensayo autorizado y evidencia mínima

| Caso | Evidencia exigida, no ejecutada aquí |
|---|---|
| Duplicado/conflicto | IDs/offsets/payload canónico, una aplicación, conflicto custodiado sin alteración del marcador. |
| Fallo DB | Sin offset/ACK prematuro; rollback, reintentos y pausa/stop; recuperar sin duplicar. |
| Restart consumidor | Identidades y source-id conservados; nueva barrera procesada y estado durable restaurado. |
| Pérdida broker | Solo broker nuevo aprobado; lag/desconexión y recuperación, ningún ACCEPTED. |
| Publicación incierta | Confirm/return/timeout observados, outbox pendiente o mismo message_id reentregado; no exactly-once ficticio. |
| Redelivery Worker | Comando idéntico produce misma operación terminal y no autorización nueva. |
| Worker BLOCKED | Fila BLOCKED y cero ACCEPTED/grants; modo disabled NO demuestra causalidad de suspensión. |
| Persistencia | Filas antes/después, barreras, logs y snapshots; no usar solo HTTP 200. |
| Readiness/backlog/lag | OFF/PAUSED/STOPPED => 503 con liveness viva; medir lag real aparte del assignment. |

Harness actual cubre V1, duplicado/barrera, comando BLOCKED y restart de seis consumidores;
no cubre por sí solo caída DB/broker, incertidumbre de confirm, DLQ ni STOMP/UI. Estos casos
requieren pasos adicionales de los propietarios y permiso de fallos sobre recursos aislados.
No hay leases, ReviewClosure externo ni cierre V2 en este ensayo seguro.

## Mac Terminal: preparación sin infraestructura

```bash
cd /Users/valeriaestefaniagongoratorres/Live-JVG
git status --short
python3 -B services/betting-suspension-service/docs/tools/test_prepare_isolated_dev3.py
python3 -B scripts/test-e2e-harness.py
python3 -B services/betting-suspension-service/docs/tools/prepare-isolated-dev3.py --token 5c84548cf006
```

El último comando imprime SQL como texto, NO lo ejecuta. Manifiesto/SQL existentes ya están
preparados: no es necesario regenerarlos ni solicitar comandos manuales para esta fase.
No se imprimió Compose resuelto ni se guardaron credenciales.

## Próximo gate: autorización requerida antes de efectos reales

Identificar proyecto, host/puertos, volúmenes NUEVOS y responsables. Aprobar expresamente
crear recursos, DB/roles y migraciones en ese proyecto, topología temporal, activación de
consumidores solo allí, publicación de fixtures y reinicios de consumidores. Aprobar aparte
fault injection (DB/brokers) y destino de outbox; no implicar leases ni aceptación.
No ejecutar docker compose config/up, SQL ni execute-v1 antes de esa aprobación.

Tras aprovisionamiento y schemas DEV2, revisión de flags/ACL y bindings, el comando preparado
(no ejecutado) es:

```bash
LIVE_JVG_E2E_APPROVED_PROJECT=live-jvg-e2e-5c84548cf006 \
  python3 scripts/e2e-harness.py execute-v1 --run 5c84548cf006 \
  --compose-file docker-compose.yml --compose-file <override-absoluto-revisado.yml>
```

Exige además LIVE_JVG_E2E_WORKER_ROUTING_KEY y vhost coherentes, registro del match en
Producer/Match State/Dashboard, consumers/storage aprobados, acceptance=false y mode=disabled.
La variable de aprobación técnica no sustituye autorización del usuario. Publica y reinicia:
no es un comando de diagnóstico. Conservar result.json, offsets, SQL y logs privados del run;
no borrar volúmenes ni sobreescribir evidencia tras fallo.

El único PR adicional justificable ahora es la corrección del generador y este gate técnico;
no se abrió PR, no se creó commit ni se publicaron cambios. Código funcional DEV3 previo
ya integrado en PR26 no se reimplementó.

## Validación local de esta ejecución

Maven verify: 330 exitosas, 20 omitidas, cero fallos/errores.
Generador offline: 4/4; harness offline: 5/5. SQL/PG/brokers/E2E nuevos: cero ejecutados.
Resultados y SHA-256 de logs: evidence/v1-e2e-readiness-results.json.

## Cierre de revisión: propuesta común para Sebastián y Gabriel

Propuesta para aprobación, sin editar Compose, harness ni el manifiesto standalone:

| Parámetro | Configuración común V1 propuesta |
|---|---|
| Proyecto / run / partido | live-jvg-e2e-5c84548cf006 / 5c84548cf006 / e2e-5c84548cf006 |
| Base del harness | live_jvg NUEVA dentro del PostgreSQL NUEVO de ese proyecto; URL interna jdbc:postgresql://postgres:5432/live_jvg para los tres DEV3, como exige preflight. |
| Schemas | archiver, betting_control y betting_worker separados. Archiver en otra DB es alternativa standalone: no pasa el harness actual sin cambio aprobado de DEV1. |
| Roles | Owners NOLOGIN y migradores/runtime separados por schema; añadir tríada Archiver. Los seis roles del manifiesto solo cubren control/worker, no son un aprovisionamiento E2E completo. |
| Migraciones | Archiver V1/V2; control V1/V2 ANTES de worker V1/V2; historiales por schema, migradores bajo owner correspondiente; runtime sin DDL. |
| Kafka | match_events dentro del broker NUEVO exclusivo; key match_id. Topic dev3_it_* del manifiesto se reserva al ensayo standalone. |
| Source IDs | ARCHIVER_SOURCE_ID y BETTING_CONTROL_SOURCE_ID = live-jvg-e2e-5c84548cf006/kafka-log-v1; estables en restart, cambiar si cambia el log físico. |
| Grupos Kafka | ARCHIVER_GROUP_ID=dev3_it_5c84548cf006_archive y BETTING_CONTROL_GROUP_ID=dev3_it_5c84548cf006_control. |
| Rabbit estado | live_updates topic y bindings score.e2e-5c84548cf006 requeridos por propietarios DEV1/2; no confundir con comandos Worker. |
| Rabbit comandos | Exchange temporal dev3_it_5c84548cf006_commands (tipo topic propuesto), cola durable dev3_it_5c84548cf006_worker, binding/routing dev3_it_5c84548cf006.command; requiere aprobación y provisión. |
| Vhost | / únicamente dentro del Rabbit NUEVO exclusivo, compatible con default del harness; configurar igual en clientes y LIVE_JVG_E2E_RABBIT_VHOST. No usar vhost de un broker ajeno. |
| Procedencia Worker | BETTING_WORKER_SOURCE_ID=live-jvg-e2e-5c84548cf006/rabbit-commands-v1, estable durante reentregas. |
| Flags | BETTING_WORKER_ACCEPTANCE_ENABLED=false, BETTING_WORKER_AUTHORIZATION_MODE=disabled, BETTING_CONTROL_PUBLICATION_ENABLED=false. Consumidores OFF hasta autorizar exclusivamente este ensayo. |
| Perfiles | Usar variables BASE del Compose actual. No activar dev3-integration que fija flags OFF y usa variables/puertos DEV3_* distintos; no mezclar ambos estilos. |

Sebastián debe preparar el override y aprovisionamiento para live_jvg sin modificar ni
apuntar al manifiesto standalone por sustitución de nombres. Gabriel debe preparar sus
schemas, storage/consumers y bindings. Esta propuesta resuelve los nombres de coordinación;
no certifica que los recursos existan o que sus permisos estén instalados.

Alcance exacto de los SELECT nuevos: control_app obtiene lectura completa de
betting_control.authorization_lease y worker_app de betting_worker.operation_authorization.
El SELECT de authorization_authority ya existía. Permiten SELECT * LIMIT 0 de readiness,
pero también leer filas: PostgreSQL no limita un GRANT SELECT a una consulta específica.
No se presentan como permisos de metadatos solamente. No añaden escritura, ownership,
membresías, CREATE, GRANT OPTION ni un emisor de leases. Las funciones EXECUTE existentes
siguen sujetas a controles transaccionales; la revisión de ACL reales sigue pendiente.
Si el equipo exige impedir lectura de filas, deberá aprobar otro mecanismo de readiness;
no se modifica aquí el Java fusionado ni se promete aislamiento mediante SELECT LIMIT 0.
