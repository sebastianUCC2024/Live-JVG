# DEV 3 — Cierre acelerado y revisión del PR #22

2026-11-16. Rama `feature/Valeria-dev3-e2e-preparation`, HEAD previo al commit
`7788295bd0fd761e97a76b31418d8c1a62d91c9f`. PR #22 confirmado MERGED con GitHub CLI.
Ya estaba integrado al iniciar esta ejecución; no se hizo pull, merge ni rebase.
Los 31 archivos originales coincidían exactamente con su manifest y se respaldaron con
verificación SHA-256 en `/private/tmp/live-jvg-dev3-delivery-7788295/before.tar.gz`.
Recuperación: extraer a un directorio vacío, comparar `before-sha256.json` y copiar solo
los archivos necesarios; no extraer sobre trabajo posterior sin revisar diferencias.

## Cambios y resultados

Se entregan los 31 archivos anteriores y se añaden snapshots de los cuatro fixtures reales
BettingCommand de PR #22, una prueba parametrizada que los consume con el decoder real,
un generador offline de provisión y sus tres tests, este informe, descripción del PR,
manifest y evidencia de verificación. No hubo necesidad de cambiar el decoder productivo.
Fixtures son propuestas compatibles, no una aprobación de ownership o transporte.

| Módulo | Exitosas | Omitidas | Fallos | Errores |
|---|---:|---:|---:|---:|
| Historical Archiver | 80 | 2 | 0 | 0 |
| Betting Suspension | 138 | 2 | 0 | 0 |
| Betting Worker | 112 | 16 | 0 | 0 |
| Total Maven verify | 330 | 20 | 0 | 0 |

Además: 3/3 tests del generador y 5/5 tests offline del harness DEV 1.
Las 20 omitidas son 17 PostgreSQL y tres probes pasivos Kafka/Rabbit.
`evidence/accelerated-delivery-results.json` contiene comandos y SHA-256 de logs completos;
`evidence/accelerated-delivery-maven.txt` conserva los resúmenes reales.
Maven verify incluye test y package. No se ejecutaron migraciones, contenedores, leases,
PostgreSQL ni brokers. La certificación 17/17 es anterior; no se atribuye a esta ejecución.
El laboratorio histórico y su evidencia se conservaron.

## Matriz contra código real de DEV 1

APROBADO significa compatibilidad técnica observada, no autorización para activar apuestas.

| Frente | Resultado | Evidencia / ajuste concreto |
|---|---|---|
| Separación de archivos | APROBADO | PR22 no modifica los archivos DEV3 candidatos; todos permanecen bajo los tres módulos. |
| BettingCommand propuesto | APROBADO | Los cuatro fixtures copiados de 7788295 pasan contra JsonCommandDecoder: válido aceptado por decoder; versión, extras y timestamps inválidos rechazados por tests locales. Decodificar no autoriza aceptar. |
| Transporte de comandos | BLOQUEADO | Sin productor/owner/topología aprobados. Harness publica un comando sintético; no es productor de negocio. |
| Base común | APROBADO, condicional | Compose ofrece misma URL; falta instalar roles/esquemas/migraciones en proyecto NUEVO. Usuario live_jvg superusuario no sirve para certificar ACL del worker. |
| Flyway/identidad Archiver | CAMBIOS REQUERIDOS en documentación DEV1 | TEAM-CONFIG pide solo V1 y CONTRACT-DECISIONS indica identidad global. Código actual requiere V1+V2 y UNIQUE(match_id,event_id). No se modificaron documentos ajenos. |
| Readiness | APROBADO | Compose usa liveness para DEV3. Readiness 503 con adaptadores OFF es intencional; no debe provocar reinicios. |
| VAR V2 | BLOQUEADO externamente | Internal ReferenceMode es V2_REVIEW, no V2_VAR_CHECK. Hay cierres parciales internos; wire sin versión no activa V2. Gol y revisión de anulación siguen pendientes. |
| Rabbit outbox | APROBADO para preparación | Confirm/mandatory/returns y retención ante incertidumbre existen; falta destino aprobado y entrega real. |
| Leases | BLOQUEADO | Verificador predeterminado deniega; no hay fuente/frescura/checkpoint aprobados. Ningún mock habilita el camino productivo. |
| Harness V1 | APROBADO offline, pendiente ejecución | Cinco tests offline pasaron; exige todos los servicios listos y consumidores explícitamente activados. Worker BLOCKED con modo disabled no demuestra causalidad de suspensión. |

## Provisión preparada, sin ejecutar

No hay en esta fase un proyecto nuevo y explícitamente autorizado para aprovisionar.
La autorización histórica del laboratorio no se reutiliza. Se preparó:

```bash
python3 -B services/betting-suspension-service/docs/tools/prepare-isolated-dev3.py --token <12-hex-nuevos>
```

El comando únicamente imprime JSON, nunca conecta. Incluye SQL pre/post migración y nombres
únicos para topic/grupos/exchange/cola/routing TEMPORALES; no publica contratos. SQL previo
exige PG17 y aborta si DB o roles existen; crea DB nueva, owners NOLOGIN, migradores y runtimes
separados. No contiene passwords; DBA debe configurarlos fuera de Git. No es certificación SQL:
los tests validan generación y guardas, no ejecutan PostgreSQL.

Aplicación futura solo con permiso explícito sobre servidor NUEVO: revisar host/puerto/proyecto,
ejecutar pre-migration con admin, migrar control V1/V2 bajo control_owner y luego worker V1/V2
bajo worker_owner (Flyway init SQL SET ROLE y schema/history propio), ejecutar post-migration
con admin, auditar ownership/ACL/SECURITY DEFINER/search_path/constraints y probar con runtime.
Nunca otorgar membresía owner a runtime ni escritura directa a operation_authorization.
El generador NO concede permisos para emitir leases. Archiver requiere base/schema propios
V1/V2 preparados por su owner; no participa en autorización atómica.

La DB generada `dev3_it_<token>_authority` sirve para ensayo independiente. Harness DEV1 fija
la DB `live_jvg`: no cambiarlo ni apuntarlo al laboratorio. Sebastián debe aprovisionar su
propio live_jvg en un proyecto NUEVO aislado, con el mismo esquema de roles/grants revisado.
No renombrar ni reutilizar una base existente para satisfacer el harness.

## Pruebas reales preparadas y límites

Los tres probes opt-in existentes solo inspeccionan metadatos (Kafka describeTopics y
Rabbit passive declarations), no prueban ACK, payload, binding, entrega ni idempotencia.
Requieren loopback, puertos no-MVP, nombres dev3_it_* y aprobación explícita. Ver comandos
y variables en `advanced-integration-preparation.md`. No se ejecutaron en esta fase.

Plan temporal para el ensayo autorizado, sin activar listeners compartidos:

| Caso real preparado | Comprobación requerida |
|---|---|
| Archiver/Suspension Kafka | Topic temporal, key match_id; comparar inbox/ledger/custodia y offsets después de commit. |
| Duplicados/conflictos | Reentregar mismos IDs y payload; una aplicación; payload distinto queda auditado. |
| DB caída/retry | No offset/ACK sin commit; cuatro intentos, pausa/stop y readiness 503; recuperación explícita. |
| Rabbit outbox | Confirm + routing y mensaje JSON antes de published_at; timeout/return conserva fila; deduplicar reenvío. |
| Worker Rabbit | Comando nuevo válido termina BLOCKED sin grant; ACK solo tras commit; reentrega no duplica operación. |
| Broker desconectado | No tratar mocks como broker; observar recuperación real, outbox retenida y consumidor sin conexión. |
| Inválido/DLQ | Custodia durable antes de ACK. Worker actual retiene y stop/requeue al agotar intentos; NO hay DLQ nueva implementada. No esperar descarte automático. Acordar DLQ o custodia con owner. |
| Restart | Reinstanciar servicios aislados; mismos IDs conservan resultado; historial y outbox pendientes recuperados. |
| Readiness/lag/backlog | Liveness no cambia por OFF; pausa/no assignment/no consumer falla readiness; medir lag real aparte. Pending/edad no certifican Rabbit disponible. |

Esto prepara procedimientos verificables, no constituye una suite E2E ejecutada. Nuevas
pruebas wire con brokers quedan sujetas al entorno autorizado; no se inventaron resultados.
Las pruebas unitarias existentes cubren retry, no ACK ante persistencia fallida, comandos
sin autorización y deduplicación; no certifican fallos físicos de brokers.

## VAR y autorización conservados

VarV2MarketClosureTest y JdbcSuspensionStoreTest cubren dos revisiones, cierre dirigido,
parcial/total, GOAL hold independiente, referencia incorrecta, duplicado, revisión atrasada
y compatibilidad V1. MATCH_CONTINUES recibido no equivale a ReviewClosure aprobada.
CLEAR no autoriza Worker: necesita transacción/grant/lease/epoch válidos; con configuración
actual la aceptación está OFF y LeaseSourceVerifier deniega. No se reinterpretó GOAL_ANNULLED.
Las suites existentes se ejecutaron otra vez; no se duplicó implementación que ya funciona.

## Configuración precisa para Sebastián

1. Elegir un solo estilo de configuración: Compose actual usa variables base ARCHIVER_*,
   BETTING_CONTROL_* y BETTING_WORKER_*; perfil dev3-integration usa DEV3_* y puertos
   18882/18883/18884 en vez de los puertos base. No mezclar perfiles y healthchecks sin adaptar.
2. Para el perfil común: dev3-integration,dev3-authority; DEV3_AUTHORITY_DB_URL idéntica,
   usuarios/passwords CONTROL y WORKER separados, Flyway false y schemas ya migrados.
3. Para Compose/harness: sus URLs jdbc:postgresql://postgres:5432/live_jvg solo en proyecto
   nuevo aislado, roles dedicados y variables base. Archiver schema archiver V1/V2;
   betting_control V1/V2 antes de betting_worker V1/V2. No login superusuario como worker.
4. ARCHIVER_SOURCE_ID y BETTING_CONTROL_SOURCE_ID deben identificar el mismo log Kafka
   físico consistentemente durante reentregas; grupos ARCHIVER_GROUP_ID y
   BETTING_CONTROL_GROUP_ID separados, nuevos y exclusivos del ensayo. No usar local/1
   para otro clúster. Kafka key=match_id; retención suficiente y orden por partición.
5. Rabbit host/port/vhost/credenciales dedicados, exchange/queue/routing temporales,
   permisos publish/consume delimitados. Worker valida exchange; binding no se crea aquí.
   Harness usa LIVE_JVG_E2E_WORKER_ROUTING_KEY y LIVE_JVG_E2E_RABBIT_VHOST.
6. Mantener BETTING_WORKER_ACCEPTANCE_ENABLED=false y AUTHORIZATION_MODE=disabled,
   BETTING_CONTROL_PUBLICATION_ENABLED=false; solo aprobar activación de consumidores
   en ensayo aislado. No activar aquí ni usar flags como aprobación implícita.
7. Mantener healthcheck liveness para servicios intencionalmente OFF. Consultar readiness
   para estado de transporte/DB, no para autorización ni lag cero.
8. El harness exige adapters habilitados y todos los schemas DEV2 instalados; la plantilla
   owner-prepared está OFF a propósito. DEV2 conserva responsabilidad de Notification,
   Dashboard, actualización de sus tests CI/Compose y confirmación de ownership del comando.

## Entrega y próximos tres pasos

Puede integrarse hoy: preparación DEV3, observabilidad, perfil común y compatibilidad de
fixtures propuestos; sin cambios DEV1/2 ni contratos compartidos. No puede activarse:
aceptación, emisor de leases, VAR V2 externo ni adapters en infraestructura compartida.

1. Revisar/publicar el commit DEV3 y abrir PR hacia dev tras autorización de push/PR.
2. Sebastián/DBA provisionan proyecto NUEVO, roles/migraciones y topología temporal;
   Gabriel instala sus schemas/adaptadores y se revisa ownership de comandos.
3. Ejecutar ensayo V1 con Worker BLOCKED y evidencias reales; aprobar por separado contratos
   VAR/autoridad y certificar fuente/leases antes de considerar aceptación.

No se hizo push ni se abrió PR. El commit local es la entrega autorizada; su hash se informa
fuera de este documento para evitar autorreferencia. Manifest: accelerated-delivery-files.txt.
