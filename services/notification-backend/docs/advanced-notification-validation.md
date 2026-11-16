# Notification — cierre independiente y compatibilidad agregada

Base de trabajo: `feature/Gabo-react-integration`,
`7f22d1290113dc8e9c68c6c84e6ab401339b8eda`, árbol inicialmente limpio.
Las referencias locales muestran el PR #21 documental integrado en origin/dev,
sin diferencias productivas de DEV 1/DEV 2; los cambios adicionales corresponden
a DEV 3. No se hizo fetch, cambio de rama, merge, commit o push.

## Decisión de versión: mantener Spring Boot 4.1.2

La diferencia respecto de Boot 3.5.7 en otros servicios no demuestra un defecto:
son procesos independientes que intercambian JSON/HTTP/AMQP, no clases Java.
Se mantiene la versión del POM sin fijar versiones nuevas ni descargar dependencias.

La [documentación oficial de requisitos](https://docs.spring.io/spring-boot/system-requirements.html)
confirma Java 17 como mínimo. Además, se verificaron el BOM/artifact local 4.1.2,
bytecode major 61, compilación release 17 y pruebas con Oracle JDK 17.0.12.
Maven utilizado: 3.9.16 ya almacenado, ejecución offline con la caché del módulo.

| Componente gestionado | Notification Boot 4.1.2 | Plataforma Boot 3.5.7 observada |
| --- | --- | --- |
| Spring Framework | 7.0.10 | 6.2.12 |
| Spring AMQP | 4.1.2 | 3.2.8 |
| Jackson MVC | 3.1.7 | 2.19.2 |
| Jackson de entrada Notification | 2.21.7, aislado del mapper MVC | No implica compartir este mapper entre servicios |
| Flyway | 12.4.0 | 11.7.2 |
| PostgreSQL JDBC | 42.7.14 | 42.7.8 |
| Maven Compiler / Surefire | 3.15.0 / 3.5.6 | 3.14.1 / 3.5.4 |

Notification usa APIs Boot 4 de health/jdbc.autoconfigure y starters específicos.
Cambiar solo el parent a 3.5.7 rompería esa selección y exigiría migración y nueva
validación. No es una corrección mínima justificada. La migración de Jackson y
sus paquetes está descrita por la
[guía oficial de Boot 4](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).
El codec Jackson 2 sigue siendo explícito/local; no reemplaza el mapper Boot MVC.
Su futura migración a Jackson 3 es deuda separada, no un bloqueo comprobado del MVP.

NotificationHttpCompatibilityTest carga el contexto real de Boot y su pipeline
MVC/Jackson, usando un puerto de repositorio simulado: confirma nombres snake_case,
contract_version 1.0, timestamps con nanosegundos, cursor, CORS exacto sin credenciales,
error DB saneado y liveness UP/readiness OUT_OF_SERVICE con infraestructura apagada.
No abre un servidor HTTP externo ni prueba una DB/broker reales.

## Defecto corregido: mensaje de confirmación durante revisión

NotificationPolicy decía «Marcador confirmado» para GOAL/GOAL_ANNULLED aun con
var_status=IN_PROGRESS. GOAL durante una revisión ya es posible en V1. El nuevo
texto usa el agregado recibido: «Revisión VAR en curso. Marcador actualizado: …».
GOAL con CLEAR conserva el texto previo. VAR_CHECK mantiene su mensaje y
MATCH_CONTINUES sigue SUPPRESSED, sin nueva alerta visible.

Esto no activa P2, no cuenta revisiones, no altera marcador, referencias, IDs,
deduplicación, contrato ni política de cierre. No modifica alertas persistidas:
la UI conserva los mensajes históricos entregados por la API.

Fixtures sintéticos claramente aislados cubren dos aperturas, gol pendiente,
cierre parcial, anulación con IN_PROGRESS y cierre final con CLEAR. Son lectura
de snapshots, no validación del motor deportivo. La prueba de llegada tardía
conserva fuente/identidad/score/timestamp y orden de recepción; no inventa una
revisión de origen ni ordena por fecha. La deduplicación/rollback siguen cubiertos
por JdbcNotificationStoreTest y los casos del listener existentes.

## Garantías revisadas que permanecen

- Inbox + alerta + contador de cursor en transacción REQUIRES_NEW; ACK después
  del retorno post-commit. El doble JDBC verifica orden, no durabilidad física.
- Clave `(source, match_id, event_id)` y conflicto de contenido sin sobrescritura;
  un redelivery no cambia la identidad de la alerta ya comprometida.
- Cola propia, prefetch 1, consumo manual, retries acotados y clasificación
  explícita de transitorios/permanentes/desconocidos.
- Custodia DLQ persistente/mandatory, confirm correlacionado sin return antes del
  ACK original. Fallo de custodia conserva original con NACK/requeue y parada.
- Readiness exige inicio, almacenamiento, conexión DB y consumidor operativo con
  consume-ok. No es comprobación continua de integridad SQL ni certificación E2E.
- Cursor por secuencia serializada evita saltar una transacción sin commit;
  restore/rotación de época y retención siguen requiriendo acuerdo operacional.

## Validación real ejecutada en esta tarea

| Comprobación local | Observado |
| --- | --- |
| Notification Maven verify offline | 112 aprobadas, cero fallos/errores/omisiones, BUILD SUCCESS |
| Script verify-local end-to-end | 112 aprobadas, salida 0, summary PASSED_LOCAL_ONLY |
| Nuevas pruebas Java | 3 de política, 2 de snapshots agregados/llegada tardía, 4 MVC/Boot: 9 sobre las 103 anteriores |
| Scripts de preparación | 8 checks negativos aprobados sin acceso externo |
| Preflight con entorno actual | Salida 2 esperada: BLOCKED_BEFORE_INFRASTRUCTURE |
| NotificationPostgresIT / NotificationRabbitIT | No ejecutadas: falta autorización/manifiesto/recursos aislados verificables |
| Imagen Docker | Dockerfile/contexto preparados; no se hizo build, pull ni arranque |
| CI compartido | No modificado; propuesta de job solo en docs |

Evidencia en `target/advanced-closure/verify-http-compatibility.log` y
`target/local-verification-20261116-175255060/` (ignorados por Git). Se preservaron
reportes anteriores. El verify normal compila las suites externas, pero las
excluye; no se cuentan como aprobadas ni se eliminan para obtener verde.

## Preparación independiente entregada

Archivos de este módulo trabajados: `src/main/java/com/live_jgv/notification/domain/NotificationPolicy.java`,
`src/test/java/com/live_jgv/notification/domain/NotificationPolicyTest.java`,
`src/test/java/com/live_jgv/notification/application/AggregateSnapshotCompatibilityTest.java`,
`src/test/java/com/live_jgv/notification/NotificationHttpCompatibilityTest.java`,
`Dockerfile`, `.dockerignore`, `scripts/verify-local.ps1`,
`scripts/test-integration-inputs.ps1`, `scripts/test-preparation-scripts.ps1`,
`docs/operational-preparation.md`, `docs/proposed-notification-ci-job.yml`,
este informe y el enlace desde `README.md`. POM y configuración de producción
permanecen sin cambios.

El Dockerfile del módulo usa el JAR empaquetado, UID/GID 10001 y requiere imagen
Java 17 Linux aprobada mediante JAVA_RUNTIME_IMAGE, sin seleccionar ni descargar
una por defecto. Bind dentro del contenedor es 0.0.0.0; storage y messaging siguen
apagados. .dockerignore deja entrar solo Dockerfile y el JAR específico, excluyendo
env, cachés, fuentes y secretos del contexto. No se alteró Compose compartido.

Los scripts y propuesta CI están descritos en
[preparación operacional](operational-preparation.md). El preflight lee entradas
sin conectar, no genera credenciales ni concede permisos; Java IntegrationGate
debe verificar manifiesto real, hashes y propiedad antes de ejecutar IT.

DEV 1 debe aprobar integración del job, imagen/Compose, harness y publicación
real. Infraestructura/DEV 3 proporciona puertos, bases, permisos, vhost y secretos
privados; migración V1 por Flyway es una acción separada autorizada. Los códigos
compartidos/VAR definitivo siguen sujetos al equipo. No hay soporte de correo,
SMS, nuevo transporte de alertas ni autorización de apuestas desde CLEAR.
