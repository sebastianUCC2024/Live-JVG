# M05 — Ejecución reproducible de pruebas durables

Esta guía no inicia servicios. Se necesita autorización independiente para
provisionar infraestructura y, en las pruebas de caída, parar un contenedor.
Nunca usar `.mvp/local.env`, bases compartidas o credenciales de producción.

## Selección segura por defecto

```powershell
mvn -Paudit-services test
npm.cmd test --prefix services/dashboard-frontend
node --test services/dashboard-backend/src/test/js/operations.test.cjs services/dashboard-backend/src/test/js/app.test.cjs services/dashboard-backend/src/test/js/loading.test.cjs
```

El POM raíz sin audit-services no contiene módulos activos. El perfil incluye
solo productor, estado y dashboard. Ningún esqueleto DEV 3 entra en el reactor.
Surefire excluye `external-integration` en estado/dashboard incluso si quedaron
variables de integración exportadas. No hace falta mantener una lista de nombres
`-Dtest=!...` para ejecutar la suite local. No usar overrides de grupos al azar.

La suite local incluye tests unitarios, JDBC/AMQP simulados, Spring memory,
WebSocket real de loopback con puerto efímero y stub HTTP local. No se etiqueta
todo eso como pruebas unitarias puras ni como integración PostgreSQL/brokers.

## Inventario de integración externa

| Módulo | Perfil / tag | Clases |
|---|---|---|
| Estado | integration-postgres / postgres-integration | JdbcMatchStateStoreIntegrationTest, DurableIngressIntegrationTest, QuarantineMigrationIntegrationTest |
| Dashboard | integration-postgres / postgres-integration | DurableSnapshotIntegrationTest, StreamEpochMigrationIntegrationTest |
| Estado | integration-brokers / broker-integration | DurableMessagingIntegrationTest, QuarantineMessagingIntegrationTest |

Todas tienen tag external-integration y extensión de seguridad antes de Spring
o JDBC. Si se seleccionan sin autorización/configuración válida, fallan antes de
conectar, en lugar de anunciar éxito por omitir todas las pruebas. El perfil
PostgreSQL no requiere Docker si el servidor desechable ya está autorizado.
No hay Failsafe ni Testcontainers añadidos: los perfiles separan selección dentro
de Surefire y no descargan un nuevo sistema de provisionamiento.

## Entorno PostgreSQL desechable

Usar PostgreSQL 17 con conexión directa/sesión (advisory locks de 9D), JDK 17 y
Maven. Si se usa Docker, requiere Engine y CLI, imagen postgres:17, puertos libres
y contenedor nuevo dedicado. No se permite reutilizar el contenedor MVP ni el
contenido de sus volúmenes. No ejecutar clean/drop como parte del runner.

El administrador prepara bases nuevas con nombres exactos revisados, por ejemplo
live_jvg_test_state, live_jvg_test_dashboard, live_jvg_test_brokers y
live_jvg_test_quarantine. Las cuentas de prueba deben ser propietarias solamente
de esas bases desechables: los tests crean esquemas, tablas, triggers y migraciones.
Eso no es el modelo de permisos runtime de M08.

Variables, suministradas por el operador sin contraseñas en argumentos:

```text
LIVE_JVG_INTEGRATION_APPROVED=true
MATCH_STATE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55439/live_jvg_test_state
MATCH_STATE_TEST_DB_USERNAME=<usuario de pruebas>
MATCH_STATE_TEST_DB_PASSWORD=<secreto desechable externo>
DASHBOARD_INTEGRATION_DB_URL=jdbc:postgresql://127.0.0.1:55439/live_jvg_test_dashboard
DASHBOARD_TEST_DB_USERNAME=<usuario de pruebas>
DASHBOARD_TEST_DB_PASSWORD=<secreto desechable externo>
```

La guarda exige loopback y nombre live_jvg_test_ seguido de letras minúsculas,
dígitos o guion bajo. Rechaza URL con usuario, query u opciones/fragmentos para
evitar credenciales embebidas y destinos ambiguos. El nombre no demuestra por sí
solo que una base sea desechable: el operador debe verificar el destino. No usar
Spring profiles adicionales que vuelvan a apuntar el datasource al MVP. Dashboard
ya no toma DB_USERNAME/DB_PASSWORD del ambiente habitual en sus pruebas durables.

```powershell
mvn -f services/match-state-service/pom.xml -Pintegration-postgres test
mvn -f services/dashboard-backend/pom.xml -Pintegration-postgres test
```

Ejecutar secuencialmente; no activar ambos perfiles en una invocación ni varios
runners contra la misma base. Las pruebas no borran la historia de despliegues,
pero algunas crean/eliminan sus propios triggers para simular rollback y fallos.
Las pruebas de upgrade dejan namespaces aislados para inspección. No confundir
cleanDisabled de Flyway con una garantía de que toda prueba sea de solo lectura.
Conservar evidencia y acordar después el ciclo de vida del entorno desechable.

## Brokers y caída de PostgreSQL (autorización adicional)

Los tests históricos esperan Kafka en localhost:9092 y RabbitMQ en localhost:5672
con guest/guest; no leen las variables del MVP para esos endpoints. Requieren
entorno **exclusivo** nuevo, sin usuarios ajenos ni procesos en esos puertos.
Versiones de referencia: apache/kafka:3.9.1 (KRaft, un broker, replication factor
1 y transacciones/offsets a una réplica), rabbitmq:4-management y postgres:17.
El operador debe provisionarlos mediante procedimiento aprobado de DEV 3, con
healthchecks, loopback y sin volúmenes compartidos. Esta fase no añade un Compose
de infraestructura ni automatiza su arranque.

Configurar el Kafka anunciado a clientes host como localhost:9092. Rabbit requiere
permisos para declarar exchange/cola/binding temporales. Los objetos usan UUID por
ejecución; no reutilizar nombres productivos. Docker CLI debe controlar solo el
contenedor PostgreSQL dedicado. Ambos tests ejecutan docker stop/start y por eso
no entran en CI normal ni en el perfil PostgreSQL.

```text
LIVE_JVG_OUTAGE_APPROVED=true
MATCH_STATE_TEST_BROKERS=true
MATCH_STATE_TEST_QUARANTINE=true
MATCH_STATE_BROKER_DB_URL=jdbc:postgresql://127.0.0.1:55439/live_jvg_test_brokers
MATCH_STATE_QUARANTINE_DB_URL=jdbc:postgresql://127.0.0.1:55439/live_jvg_test_quarantine
MATCH_STATE_TEST_POSTGRES_CONTAINER=live-jvg-7h-7i-<sufijo dedicado nuevo>
```

También se requieren LIVE_JVG_INTEGRATION_APPROVED y credenciales TEST del bloque
anterior. El prefijo 7h-7i permite ambos tests históricos; para solo 7H se admite
live-jvg-7h-. Verificar manualmente que ese contenedor corresponde al puerto y a
las bases anteriores: un prefijo no autentica infraestructura. Nunca marcar una
infraestructura compartida como desechable solo para superar la guarda.

```powershell
mvn -f services/match-state-service/pom.xml -Pintegration-brokers test
```

No suministrar ni imprimir contraseñas en CLI/logs. Los informes antiguos 7H/7I
son evidencia histórica: sus nombres de bases sin live_jvg_test_ ya no pasan esta
guarda. Los tests Playwright con reinicio de backend son otra integración opt-in;
no forman parte de esta suite ni de CI. No ejecutarlos en esta etapa.

## Evidencia requerida antes de aceptar integración

Registrar versiones, revisión Git, base/contenedor exclusivo, selección de tests,
resultados y conteos (incluidos skipped). Verificar recuperación, offsets,
outbox/inbox y época sin republish histórico. No declarar integración aprobada
con cero pruebas ejecutadas, falta de conexión o tests excluidos. En 9E solo se
ejecuta la validación local y las guardas con mapas de entorno simulados.

Referencias: [Surefire grupos/tags](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html).
