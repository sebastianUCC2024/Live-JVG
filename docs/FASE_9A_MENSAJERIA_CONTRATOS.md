# Fase 9A — Mensajería y referencias opcionales

Fecha: 15 de noviembre de 2026. Rama: `fix/auditoria-integral-fase9`.
Alcance exclusivo: H01 y H02. No se implementan las etapas 9B–9F.

## H01 — Consumidor RabbitMQ durable

Problema verificado: `ScoreProjection.receive()` utilizaba la fábrica por defecto
y una cola sin DLQ. La política anterior solo estaba activa en perfil `memory`.

Solución:

- El listener `durableScores` usa `durableScoresFactory`, ACK automático después
  del procesamiento/recuperación, un consumidor y prefetch 1.
- Fallos transitorios: hasta tres intentos totales; espera exponencial a partir
  de `live.retry.interval-ms` (1000 ms por defecto), factor 2 y máximo 4 veces
  el intervalo inicial. Los valores deben ser positivos.
- JSON inválido y errores de validación, incluido conflicto de identidad:
  permanentes, sin reintentar el procesamiento.
- Tras un error permanente o agotamiento del presupuesto, se conserva el cuerpo
  original y se republica una copia persistente en `<live.queue>.dlx`, exchange
  direct durable, routing `rejected`, enlazado a `<live.queue>.dlq`, durable.
- Publisher confirms correlacionados, returns y mandatory habilitados. El
  original solo puede confirmarse tras recibir ACK sin devolución de la copia.
- Si la transferencia falla, recibe NACK/return o no se confirma en cinco
  segundos, se solicita la parada asíncrona del listener y la reencolación del
  original. No se reintenta indefinidamente el mensaje inválido dentro del
  listener. La recuperación operativa exige corregir la causa y reiniciar el
  listener o servicio de forma autorizada.

### Compatibilidad con colas existentes

Se mantienen los argumentos de la cola durable de entrada. No se le añaden
unilateralmente argumentos DLX: RabbitMQ rechazaría una redeclaración incompatible
de una cola existente. La transferencia a la DLQ se hace en la aplicación con
confirmación explícita; no depende de una política administrativa del broker.
No se elimina, renombra ni purga ninguna cola; no se modifica Compose.

La transferencia no es una transacción distribuida: puede producir duplicados si
se pierde el ACK o el proceso cae después de publicar en la DLQ. El original y su
identidad se conservan. Los consumidores/herramientas de la DLQ deben tolerar
entrega al menos una vez; no se implementa replay automático.

La DLQ recibe el mensaje original, incluidos posibles eventos históricos que el
decoder actual no acepte. No se corrige ni normaliza su contenido silenciosamente.
El perfil `memory` conserva su política previa.

## H02 — Referencias opcionales compatibles

Problema verificado: un GOAL nuevo podía llevar una referencia vacía, crear una
salida de estado y ser rechazado por el dashboard, que exige null o texto no vacío.

Solución aplicada a nuevas publicaciones:

- `OutgoingReferencePolicy` valida null o cadena no vacía en el servicio del
  productor y en su adaptador Kafka, incluso si se invoca directamente el puerto.
- `MatchStateService` valida antes de persistir una transición nueva y su outbox.
- `DurableEventIngress` clasifica ese rechazo antes de aplicar el evento, como
  `DOMAIN_INVALID` en cuarentena. Conserva el payload/receipt originales; no
  convierte el rechazo permanente en una secuencia de retries de infraestructura.
- `GOAL_ANNULLED` continúa requiriendo referencia; null no sustituye una
  referencia obligatoria. Las referencias válidas, eventId y timestamp se preservan.

### Eventos históricos

No cambian `MatchEvent`, `MatchState`, migraciones ni schemas compartidos.
La reconstrucción del ledger mantiene las reglas anteriores. Una repetición
exacta de un evento ya aceptado sigue sin crear nueva salida, aunque contenga
una referencia histórica vacía. No se reescriben IDs, timestamps ni referencias.

Una solicitud nueva que antes admitía referencia opcional vacía ahora se rechaza;
por REST, el servicio produce IllegalArgumentException y el controlador existente
la convierte en 400. El adaptador directo devuelve un future fallido sin enviar
a Kafka. Una entrada nueva de estado incompatible se guarda en cuarentena.
La compatibilidad se endurece deliberadamente para un valor que el consumidor
ya rechazaba; no cambia la semántica de referencias válidas.

Pendientes históricos incompatibles en una outbox anterior no se transforman:
pueden llegar a la nueva DLQ y requieren revisión explícita del equipo.

## Pruebas y evidencias

Pruebas nuevas/ampliadas:

- `DurableScoreRetryTest`: decoder real con entrada inválida; receptor durable
  real con JDBC simulado; clasificación envuelta; retry transitorio; agotamiento;
  conflicto; ACK/NACK, return y timeout de DLQ; preservación de bytes; topología
  compatible y selección de fábrica; solicitud de parada del listener registrado.
- `MatchSimulationServiceTest`: referencias vacías para los cuatro tipos,
  preservación de referencias válidas e identidad; anulación sin referencia.
- `MatchStateServiceTest`: ningún cambio/outbox ante referencia nueva inválida;
  duplicado histórico preservado sin nueva escritura.
- `DurableReferencePolicyTest`: clasificador durable y transacción con JDBC
  simulado; rechazo permanente enviado a cuarentena, sin aplicación ni publicación.
- `Dev1LiveWireCompatibilityTest`: compila fuentes reales de los módulos DEV 1
  con el JDK y verifica serialización Kafka → DTO de entrada → dominio/estado →
  ScoreUpdate → decoder DEV 2. Cuatro eventos, referencias y nanosegundos; también
  valida bloqueo del adaptador ante blancos y lectura del valor histórico.

La prueba entre servicios no duplica DTO ni utiliza fixtures estáticos para la
salida actual. Compila en `dashboard-backend/target/phase9-contract-classes` y
requiere el checkout de los módulos hermanos y un JDK. No incorpora nuevas
dependencias Maven. El transporte Kafka, JDBC y las confirmaciones RabbitMQ se
simulan: sus logs de ACK no demuestran entrega a brokers reales.

Resultados de la ejecución de cierre:

| Componente | Pruebas | Fallos | Errores |
|---|---:|---:|---:|
| live-feed-producer | 54 | 0 | 0 |
| match-state-service | 81 | 0 | 0 |
| dashboard-backend | 99 | 0 | 0 |
| Total | 234 | 0 | 0 |

Comandos usados con caché Maven ignorada del checkout, en modo offline después
de descargar las dependencias ya declaradas que faltaban:

```text
mvn -o -Dmaven.repo.local=<checkout>/.m2/repository -f services/live-feed-producer/pom.xml test
mvn -o -Dmaven.repo.local=<checkout>/.m2/repository -f services/match-state-service/pom.xml -Dtest=!*IntegrationTest test
mvn -o -Dmaven.repo.local=<checkout>/.m2/repository -f services/dashboard-backend/pom.xml -Dtest=!DurableSnapshotIntegrationTest test
```

Los reportes Surefire y logs `target/phase9-test.log` son locales e ignorados.
El contador skipped de estas selecciones es cero: las pruebas reales fueron
**excluidas explícitamente**, no deben confundirse con pruebas ejecutadas.
Se excluyeron cinco clases PostgreSQL/brokers de estado y la clase durable
PostgreSQL del dashboard. Las pruebas WebSocket locales usan perfil memory y
Rabbit deshabilitado. No se ejecutaron pruebas React porque no se modificó frontend.

Los intentos `verify` pasaron sus tests, pero fallaron después en `repackage`
al renombrar los JAR a `.jar.original`. No se borraron esos archivos ni se
detuvieron procesos para liberar posibles bloqueos. El cierre usa `test`;
el empaquetado ejecutable queda pendiente de una verificación independiente.

## Riesgos residuales y siguientes pasos

H01 y H02 quedan corregidos en código y pruebas sin brokers. Antes de certificar
la integración real se necesita autorización para validar sobre recursos aislados:
topología, permisos del DLX, transferencia confirmada, retención al perder el ACK,
parada efectiva del contenedor de listener y recuperación tras restaurar la DLQ.
Un listener detenido requiere supervisión explícita: la salud de conexión Rabbit
por sí sola no acredita que ese consumidor esté procesando.

No se modificaron roles PostgreSQL, credenciales, contratos compartidos, modelos
históricos, código de DEV 3 ni infraestructura. No hubo commits, push o merges.

Propuesta de commits, sin ejecutarlos:

1. DEV 2: política durable de retry y transferencia confirmada a DLQ con tests H01.
2. DEV 1: validación de nuevas referencias y clasificación durable, con tests H02.
3. Equipo: prueba entre módulos y documento de cierre 9A.

DEV 3 no necesita cambios de esqueleto en esta etapa; el equipo de infraestructura
deberá revisar permisos/operación de la DLQ antes de la prueba real autorizada.
Se espera autorización antes de empezar 9B.
