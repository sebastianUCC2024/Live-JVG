# Fase 9D: protección, diagnóstico y decisiones previas

Documento del punto de autorización previo. La autorización posterior permite
implementar 9D; el estado vigente está en el
[informe de implementación](FASE_9D_CONCURRENCIA_OUTBOX_RENDIMIENTO.md).
Se conserva el diagnóstico siguiente como antecedente, no como estado final.

Fecha: 15 de noviembre de 2026. Rama: `fix/auditoria-integral-fase9`.
Estado: diagnóstico y documentación; implementación detenida por la autorización
de persistencia de M09 exigida por el usuario. **9D no está terminada. No iniciar 9E.**

## 1. Git y protección del trabajo

Inventario inicial expandido: 49 archivos, 27 modificados y 22 nuevos, sin staging.
La rama coincide con la solicitada. No se han hecho commits, stash, push, merge,
reset, limpieza ni cambios destructivos.

Respaldo real fuera del repositorio:
`C:\Users\JUANSE\AppData\Local\Temp\LIVE-JVG-pre9D-20261115-024303`.
Contiene los 49 archivos bajo `files/`, `manifest.json` con ruta y SHA-256,
`tracked.patch` binario, `git-status.txt` y `HEAD.txt`.
Cada copia fue comparada por SHA-256 con su original antes de iniciar el análisis.
El manifiesto es también el inventario exhaustivo de los 49 archivos.
Para recuperar, primero comparar el manifiesto con el árbol actual y seleccionar
qué restaurar; no aplicar el patch encima de cambios existentes ni sobrescribir
el árbol automáticamente. Restaurar o descartar cambios requiere autorización.
La carpeta temporal no tiene garantía de retención: conviene conservar una copia
adicional en almacenamiento elegido por el usuario antes de limpiar temporales.

La revisión de los 49 archivos no encontró artefactos de compilación, archivos
.env reales, claves privadas ni patrones de tokens habituales. Las plantillas
contienen marcadores (`change_me`, `REPLACE_*`) y Compose referencias a variables;
no se cambiaron credenciales. Esto no certifica el contenido de archivos ignorados,
el historial Git ni la ausencia de toda posible cadena secreta.

## 2. H05: coordinación de outbox

`ProcessAndPublishMatchEvent` sincroniza una JVM completa, no cada partido ni
varias instancias. `JdbcMatchStateStore.pendingForMatch` lee sin reclamar; dos
publicadores pueden leer la misma fila y enviarla antes de `markPublished`.
El CAS del catálogo protege la escritura del estado, no la publicación posterior.
`DurableEventIngress` publica después de confirmar su transacción, lo que debe
preservarse. La recuperación inicial vuelve a drenar las salidas pendientes.

El publicador Rabbit conserva payload y destino almacenados, usa mensajes
persistentes, espera confirm y rechaza NACK/return. La confirmación precede a
`published=true`. Una caída después de enviar o confirmar y antes de marcar deja
la fila pendiente: se reenvía. Es entrega al menos una vez; no se debe marcar
antes de enviar ni convertir timeout en éxito. El inbox del dashboard deduplica
por `(match_id,event_id)` y rechaza contenido distinto para la misma identidad.

Alternativas investigadas:

| Alternativa | Esquema | Consecuencias |
|---|---|---|
| FOR UPDATE SKIP LOCKED durante envío | No | Mantiene transacción y locks durante red; saltar una fila del mismo partido puede alterar el orden. No recomendado. |
| Reclamo con token y vencimiento | Sí | Transacciones cortas; confirmar/reclamar exige propietario y generación. Una pausa superior al lease permite un publicador obsoleto: no garantiza exclusión del envío a Rabbit. Requiere autorización y diseño de fencing. |
| Advisory lock de sesión por partido | No | Mantiene una conexión dedicada durante el envío, sin transacción abierta. Serializa los publicadores cooperantes por partido mientras la sesión permanece válida. |

Propuesta sin migración: advisory lock de sesión por partido con namespace estable,
conexión física retenida, autocommit y prohibición de transacción ambiente en el
tramo de red. Adquirir antes de leer pendientes, volver a leer bajo lock, consumir
por lotes acotados en application_seq y marcar solo tras confirmación. Todas las
rutas, incluida recuperación inicial, deben usar el mismo coordinador. Liberar
en finally sobre la misma conexión; no devolver conexiones con locks al pool.
Las colisiones de hash solo deben serializar partidos adicionales, nunca reducir
la exclusión. El resultado de lock ocupado debe ser reintentable y no interpretado
por el runner como recuperación completada. Un fallo de conexión debe detener
el publicador y evitar cualquier reconexión transparente dentro de la sección.

Caída: el servidor libera el lock al finalizar la sesión; lo no marcado sigue
pendiente para otro publicador. Deben configurarse límites de espera y detección
de conexiones rotas. Una partición PostgreSQL con Rabbit aún accesible deja una
ventana para un envío ya en vuelo del propietario anterior: un advisory lock no
es fencing de Rabbit. Ni este diseño ni un lease pueden prometer ausencia absoluta
de envíos simultáneos bajo toda partición. Hace falta acordar ese límite antes
de declarar H05 cerrado. La idempotencia del consumidor sigue siendo obligatoria.

Fuentes primarias: [locks PostgreSQL 17](https://www.postgresql.org/docs/17/explicit-locking.html),
[SELECT y SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html),
[confirms RabbitMQ](https://www.rabbitmq.com/docs/confirms) y
[reintentos y duplicados](https://www.rabbitmq.com/docs/reliability).
El tratamiento de particiones anterior es una inferencia de arquitectura aplicada
al uso de dos sistemas independientes, no una garantía ofrecida por esos locks.

Resultado: diagnóstico y propuesta; no se ha cambiado la coordinación ejecutable.

## 3. H06: una sola instancia

Restricción aprobada incorporada en [modos de despliegue](MODOS_EJECUCION.md).
`DashboardWebSocketConfiguration` usa `enableSimpleBroker`; difusión local a JVM.
Los consumers Rabbit de una misma cola compiten, no hacen broadcast a todas las
instancias. Una base compartida no hace distribuido al broker simple STOMP.
Compose tiene un puerto de host fijo, no una garantía de singleton entre hosts.

No se implementó relay STOMP, no se añadieron servicios ni dependencias, ni se
modificó Compose. Pendiente reforzar y probar el impedimento de escalado local;
`deploy.replicas: 1` por sí solo es un default, no una prohibición de overrides.

## 4. M02: historiales

`ScoreProjection.view` agrega y decodifica todo inbox cada segundo por cliente
del frontend estático. Necesita solo 30 eventos recientes más todos los goles
activos. Debe conservarse la sentencia única de 9C para no mezclar marcador e
historia de distintas imágenes MVCC.

Propuesta sin migración: dos subconsultas dentro de la misma sentencia de view:
últimos 30 con ORDER BY seq DESC LIMIT 30 antes de agregar y goles activos con
NOT EXISTS de GOAL_ANNULLED que referencie al GOAL. Preservar orden, payloads,
nulls, ausencia/404 y operación confirmada fuera de la ventana reciente. La
consulta de goles debe reproducir el orden de aceptación; no confundir timestamp
con seq. No borrar historia ni limitar a 30 el conjunto de goles activos.

El índice UNIQUE(match_id,event_id) ayuda a seleccionar por partido; el PK(seq)
permite recorrido inverso. No existe índice (match_id,seq) del dashboard: no se
promete lectura física de solo 30 filas para múltiples partidos ni coste O(1).
Los filtros sobre payload TEXT requieren parseo JSON; el anti-join necesita
medición con EXPLAIN real. Acotar filas transferidas reduce memoria Java pero
no demuestra por sí mismo reducción del escaneo SQL. Si un índice nuevo resulta
necesario se presentará primero la migración, sin aplicarla automáticamente.

`JdbcMatchStateStore.load` debe conservar historia completa: el dominio valida
identidades y referencias antiguas y LoadedMatch exige revision=eventos.size().
Aplicar LIMIT aquí rompería idempotencia e invariantes. Repetir MatchState.apply
al reconstruir copia mapas inmutables crecientes, con coste cuadrático. Registrar
el catálogo también llama load solo para validar equipos, lectura completa evitable.
Outbox pendiente se materializa completa y requiere lectura por lotes bajo la
coordinación de H05, sin que un LIMIT aislado deje salidas sin drenar.

Alternativas: primero lecturas acotadas de dashboard y catálogo; después evaluar
rehidratación transitoria que reutilice las mismas validaciones de dominio con
pruebas de equivalencia. No introducir snapshots persistentes, tablas ni caché
que oculte restauraciones o escrituras de otra instancia. Si el objetivo exige
acotar la memoria total del agregado, presentar alternativa de almacenamiento:
el contrato actual exige conservar todos los eventos procesados.

Pruebas nuevas pendientes tras autorización de continuación: miles de eventos,
anulación de un gol fuera de los 30 recientes, referencias e IDs antiguos,
orden estable y comparación de estado reconstruido. Mantener las barreras de
lectura consistente de 9C. No confundir pruebas JDBC simuladas con EXPLAIN real.

Resultado: diagnóstico y alternativas; no se ha optimizado código aún.

## 5. M09: autorización requerida

Hoy snapshot durable usa `stream_id=durable-<matchId>` y revision=inbox.seq.
SnapshotStore descarta revisiones menores o iguales del mismo stream, incluso
al recuperar tras reconexión. Una restauración que retrocede seq conserva ese
stream_id y deja clientes con datos anteriores a la restauración.
Memory ya usa un UUID por instancia, apropiado para su estado volátil; no debe
copiarse esa estrategia al servicio durable porque reiniciaría el stream en cada
despliegue normal. Ni seq, event_id ni timestamps identifican de forma fiable
una época de base restaurada. Una época guardada en la propia base también
retrocede con su backup: se necesita un paso explícito de restauración o una
autoridad persistente externa a ese backup.

Plan recomendado, **sin crear ni aplicar todavía migración**:

1. Migración Flyway aditiva del dashboard: tabla `stream_epoch` con clave singleton
   y `epoch UUID NOT NULL`, sembrada una sola vez. Generar UUID en Java y hacer
   INSERT ON CONFLICT DO NOTHING evita requerir extensiones PostgreSQL nuevas.
   No alterar inbox, seq, payloads ni migraciones existentes.
2. Leer época y última fila inbox en la misma sentencia de snapshot para que una
   rotación no mezcle épocas y revisiones. No cachear la época de forma indefinida.
3. Usar `durable-<epoch>-<matchId>` en el campo stream_id existente. Mantener
   tipos y campos REST/STOMP y revision=seq. El frontend actual trata stream_id
   como opaco y acepta revisión menor tras recuperar un stream diferente.
   Se solicita aprobación explícita del cambio semántico de identidad; no se
   modificará ningún contrato compartido sin ella.
4. Runbook de restauración: detener dashboard y entradas, restaurar, rotar época
   una sola vez, confirmar y reanudar. Reconstrucción/truncado de inbox sigue el
   mismo procedimiento. Un reinicio normal NO rota. Reiniciar dashboard fuerza
   reconexión y recuperación; no rotar en caliente mientras haya sesiones vivas,
   porque apply descarta streams distintos fuera de la recuperación.
5. Migración inicial y restauración desde backup sin tabla deben crear una época;
   restauración desde backup con tabla debe rotarla igualmente. Coordinar bases,
   colas y replay: época nueva corrige aceptación del cliente, no coherencia de
   mensajes históricos pendientes restaurados en otros sistemas.
6. Pruebas: reinicio ordinario conserva época; nueva época con revision inferior
   es aceptada al recuperar; misma época inferior se ignora; handlers de conexión
   vieja no sobrescriben; eventos antiguos se limpian al cambiar época. Añadir
   integración opt-in de persistencia/restauración, pendiente de infraestructura.

Alternativa sin migración: UUID persistente externo administrado por despliegue,
conservado en reinicios y rotado al restaurar. Evita tabla, pero incorpora una
dependencia operativa y debe fallar ante ausencia o formato inválido. También
requiere aprobar el nuevo significado del stream_id y el procedimiento de rotación.
No es suficiente aceptar siempre revisiones menores en el frontend: reintroduce
regresiones por respuestas atrasadas que 9A-9C ya evitan.

## 6. Validación y regresiones

Regresión local ejecutada tras el diagnóstico:

| Suite | Pruebas | Fallos | Errores | Skipped |
|---|---:|---:|---:|---:|
| Dashboard Java | 115 | 0 | 0 | 0 |
| Productor Java | 57 | 0 | 0 | 0 |
| Estado Java | 81 | 0 | 0 | 0 |
| React/Vitest | 58 | 0 | 0 | 0 |
| Scripts estáticos/Node | 23 | 0 | 0 | 0 |
| Total | 334 | 0 | 0 | 0 |

Las suites incluyen categorías diferentes; no se presentan 334 como pruebas
unitarias puras. Unitarias: dominio, servicios, validaciones y políticas con
mocks. Simuladas: JDBC/MVCC, confirms/reintentos AMQP, transporte STOMP, DOM/HTTP
del frontend. Pruebas locales con Spring, WebSocket memory y stub HTTP loopback
no usan infraestructura distribuida real. Los totales anteriores son por runner,
no un desglose numérico por categoría.

Comandos ejecutados desde la raíz (sin install, clean, package ni verify):

```powershell
mvn -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -f services/dashboard-backend/pom.xml '-Dtest=!DurableSnapshotIntegrationTest' test
mvn -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -f services/live-feed-producer/pom.xml test
mvn -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -f services/match-state-service/pom.xml '-Dtest=!*IntegrationTest' test
npm.cmd test --prefix services/dashboard-frontend
node --test services/dashboard-backend/src/test/js/operations.test.cjs services/dashboard-backend/src/test/js/app.test.cjs services/dashboard-backend/src/test/js/loading.test.cjs
```

Los tres Maven finalizaron BUILD SUCCESS; logs en cada target/phase9d-test.log.
No hubo regresiones detectadas en la suite local de 9A-9C. No se certifica
regresión de despliegue o integración real ni empaquetado de nuevos JAR.
Se excluyeron explícitamente DurableSnapshotIntegrationTest del dashboard y las
cinco clases *IntegrationTest de estado (DurableIngress, DurableMessaging,
JdbcMatchStateStore, QuarantineMessaging y QuarantineMigration). No se cuentan
como ejecutadas ni como skipped del selector. Pendientes también concurrencia
PostgreSQL real, restauración real, EXPLAIN y fallos de publicadores reales.
No se han añadido pruebas que pretendan validar soluciones todavía no implementadas.
Las pruebas con JDBC/AMQP simulado no acreditan exclusión PostgreSQL real.
No iniciar servicios PostgreSQL, Kafka, RabbitMQ, contenedores o scripts de arranque.

## 7. Archivos y alcance

Esta intervención solo cambia `docs/MODOS_EJECUCION.md` y añade este informe.
Estado resultante: 50 archivos acumulados (27 modificados y 23 nuevos), sin staging.
Se volvió a verificar el respaldo: cero discrepancias de hashes. De los 49
originales, solo MODOS_EJECUCION.md cambió durante esta intervención.
Los 49 archivos iniciales están respaldados. No se modifican contratos compartidos,
esqueletos DEV 3, esquema, dominio o código ejecutable. Los logs de test se generan
en target ignorados. Riesgos H05/M02/M09 siguen abiertos; H06 queda documentado
con prevención técnica adicional pendiente. Requiere autorización de M09 para
reanudar implementación; no se considera terminada la fase ni se inicia 9E.
