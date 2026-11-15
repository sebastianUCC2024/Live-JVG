# Fase 9E — Contratos, calidad y CI/CD

Fecha: 15 de noviembre de 2026. Alcance autorizado: M04, M05, M08, L01, L02.
Finalizada la implementación/validación local permitida. Las aprobaciones de
contratos y permisos, integración externa y ejecución remota de CI siguen
pendientes. No se inicia 9F.

## 1. Git inicial/final y protección

Rama obligatoria conservada: `fix/auditoria-integral-fase9`.
HEAD inicial/final: `53d714c5542bf0e997bda57be7d043847803dffd`.
Inicial: 69 archivos (35 modificados y 34 nuevos). Final: 89 archivos acumulados
(44 modificados y 45 nuevos). Sin staging. No hubo commit, stash, push, merge,
cambio de rama, limpieza de artefactos ni eliminación de respaldos.

Antes de 9E se detectó que los respaldos temporales no cubrían 26 estados de
archivo de 9D. Se detuvo el trabajo hasta autorización expresa. El respaldo
integral posterior está en:
`C:\Users\JUANSE\Documents\LIVE-JVG-Backups\pre9E-20261115-031530-bd09a36f`.
Sus 69 archivos, rutas, tamaños y SHA-256, HEAD/rama/status y patch binario quedaron
verificados antes de implementar y volvieron a verificarse: cero errores.
Los respaldos anteriores permanecen intactos. No se detectaron secretos ni
artefactos en el inventario Git; esto no certifica archivos ignorados o todo el
historial del repositorio. No se imprimieron credenciales del ambiente.

## 2. Inventario exclusivo de 9E

28 archivos intervenidos en esta fase, comparados con el respaldo integral y con
los archivos previamente limpios de HEAD; no confundir con el diff acumulado 9A-9D:

```text
.github/workflows/validation.yml
docs/CONTRATOS_VERSIONADOS_PROPUESTA.md
docs/FASE_9E_CONTRATOS_CALIDAD_CI.md
docs/POSTGRES_MINIMO_PRIVILEGIO_PROPUESTA.md
docs/PRUEBAS_INTEGRACION_DURABLE.md
docs/VALIDACION_CI.md
pom.xml
README.md
services/dashboard-backend/pom.xml
services/dashboard-backend/README.md
services/dashboard-backend/src/main/resources/static/app.js
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/Dev1LiveWireCompatibilityTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/DurableSnapshotIntegrationTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/StreamEpochMigrationIntegrationTest.java
services/dashboard-backend/src/test/java/com/live_jgv/dashboard/ValidationPipelineTest.java
services/dashboard-backend/src/test/java/com/live_jgv/test/DurableIntegrationSafety.java
services/dashboard-backend/src/test/java/com/live_jgv/test/DurableIntegrationSafetyTest.java
services/dashboard-frontend/README.md
services/dashboard-frontend/src/services/dashboardStream.ts
services/live-feed-producer/pom.xml
services/match-state-service/pom.xml
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/jdbc/DurableIngressIntegrationTest.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/jdbc/DurableMessagingIntegrationTest.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/jdbc/JdbcMatchStateStoreIntegrationTest.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/jdbc/QuarantineMessagingIntegrationTest.java
services/match-state-service/src/test/java/com/live_jgv/matchstate/infrastructure/jdbc/QuarantineMigrationIntegrationTest.java
services/match-state-service/src/test/java/com/live_jgv/test/DurableIntegrationSafety.java
services/match-state-service/src/test/java/com/live_jgv/test/DurableIntegrationSafetyTest.java
```

No se modifican contratos/events, migraciones SQL, Compose, permisos reales o
esqueletos DEV 3. Las únicas variaciones productivas de frontend son un comentario
y la etiqueta «eventos recientes», sin rediseño ni cambios de interacción.

## 3. M04 — Contratos compartidos

Los cuatro schemas están vacíos (0 bytes), no son schemas JSON válidos.
[Propuesta versionada](CONTRATOS_VERSIONADOS_PROPUESTA.md): campos observados,
obligatorios/nullable, enums, IDs no necesariamente UUID, referencias, límites,
orden, compatibilidad y evolución con consumidores estrictos. LiveAlert y
BettingCommand siguen sin definición aprobada; no se implementan por inferencia.

Se amplía la compatibilidad de fuentes reales productor→estado→decoder dashboard:
campos exactos, cuatro tipos, VAR, referencias y precisión. Se prueba referencia
nullable ausente y que un campo schema_version nuevo sería rechazado. No se cambia
wire ni se añade versionado implícito. El acuerdo del equipo y un validador JSON
Schema real quedan pendientes: no se presenta la propuesta como contrato aprobado.

## 4. M05 — Integración aislada

Estado/dashboard excluyen por defecto external-integration mediante Surefire.
Perfiles integration-postgres e integration-brokers seleccionan tags separados;
WebSocket local memory sigue dentro de la suite segura. Las siete clases externas
tienen una guarda JUnit evaluada antes de Spring/JDBC/Docker, sin depender solo
de EnabledIfEnvironmentVariable que pudiera omitir silenciosamente toda la suite.

La guarda exige autorización explícita, base nueva live_jvg_test_* en loopback,
credenciales TEST y URL sin credenciales/opciones. Broker/outage exige además
aprobación de parada y flags/prefijos históricos dedicados. Dashboard deja de
usar DB_USERNAME/DB_PASSWORD habituales para integración; ahora requiere
DASHBOARD_TEST_DB_USERNAME/PASSWORD. Las pruebas de guardas usan mapas ficticios.

[Guía reproducible](PRUEBAS_INTEGRACION_DURABLE.md): inventario de clases, comandos,
variables, Docker/versiones/puertos, restricciones de bases y requisitos de
outage. No provisiona infraestructura compartida ni garantiza que un nombre de
base prefijado por sí solo haga seguro su contenido. Las pruebas históricas de
caída sí detienen PostgreSQL al autorizarlas; no se han ejecutado aquí.

## 5. M08 — Mínimo privilegio propuesto

[Plan de privilegios](POSTGRES_MINIMO_PRIVILEGIO_PROPUESTA.md): dos migradores
propietarios solo de su schema, dos runtimes sin DDL/ownership, y operador de
restauración separado. Matriz DML por tabla/columna derivada de SQL real,
secuencia inbox_seq, época de solo lectura para runtime, y ausencia de permisos
cruzados o acceso runtime a historial Flyway.

Se identificó el usuario bootstrap compartido actual y migraciones con datasource
runtime. Se propone migrar por separado y arrancar después con Flyway deshabilitado
y cuentas mínimas; no se cambia el default del MVP sin provisionamiento previo.
El documento distingue permisos heredados de PUBLIC, roles propietarios y permisos
efectivos: GRANT adicionales por sí solos no reducen privilegios existentes.
No se ejecutó CREATE ROLE, GRANT, REVOKE o migración real. Aplicación y pruebas
negativas de permisos requieren revisión/aprobación de DEV 3.

## 6. L01 — Build y CI/CD

El POM raíz sigue siendo descriptor sin módulos por defecto, con audit-services
explícito para los tres servicios reales. Se ejecutó ese reactor: test en los
tres módulos y BUILD SUCCESS. No entran notification/apuestas/archiver.

Workflow nuevo con matriz Java 17 por servicio y mvn verify, React Node 24
npm ci/test/build y Node tests. Sin skipTests en CI, sin servicios Docker,
secretos productivos, publicación de imágenes, despliegues o permisos de escritura.
Checkout no persiste credenciales. La política YAML se verifica en tests locales.
El workflow no se ha subido ni ejecutado en GitHub; no se declara un run remoto verde.

Perfil isolated-package permite verificar repackage en una carpeta nueva sin
sobrescribir JAR previos. [Comandos y límites](VALIDACION_CI.md). No se añadieron
dependencias Maven/npm ni se cambiaron lockfiles. Las acciones oficiales usan tags
mayores verificados, no pins SHA inmutables; queda sujeto a política del equipo.

## 7. L02 — Documentación/frontend

README raíz explica reactor, CI y separación entre validar y arrancar el MVP.
README dashboard distingue documentación memory original, puertos durable/memory,
prod con memory explícito, singleton y evidencia histórica/actual. Se elimina la
afirmación obsoleta de Compose no modificado y se evitan instrucciones clean para
validación local. README React ya no presenta bloqueos de Fase 7 como estado actual.

El contador estático dice eventos recientes porque representa la ventana de 30,
no el total almacenado. El comentario de deduplicación React explica que el límite
10.000 es caché del cliente y no retención del inbox durable. Nombres/descripciones
de módulos Maven ya no están vacíos. No se cambian controles H03, carga M03,
reintentos, UX o configuración efectiva del MVP.

## 8. Pruebas ejecutadas y omitidas

Sin sumar repeticiones focalizadas ni builds con tests omitidos:

| Runner | Pruebas | Fallos | Errores | Skipped |
|---|---:|---:|---:|---:|
| Productor Java | 57 | 0 | 0 | 0 |
| Estado Java | 95 | 0 | 0 | 0 |
| Dashboard Java | 123 | 0 | 0 | 0 |
| React/Vitest | 59 | 0 | 0 | 0 |
| Scripts Node | 23 | 0 | 0 | 0 |
| **Total** | **357** | **0** | **0** | **0** |

Unitarias: dominio/políticas/guardas y validadores. Simuladas: JDBC/MVCC, locks,
confirmaciones AMQP, STOMP/DOM y compatibilidad wire con fuentes reales. También
se ejecutan contextos Spring memory, WebSocket y stub HTTP locales. No se cuentan
357 como unitarias puras ni como integración distribuida real.

Comandos principales:

```powershell
mvn -o "-Dmaven.repo.local=C:/Live-JVG/.m2/repository" -Paudit-services test
npm.cmd test --prefix services/dashboard-frontend
npm.cmd run build --prefix services/dashboard-frontend -- --outDir target/phase9e-dist
node --test services/dashboard-backend/src/test/js/operations.test.cjs services/dashboard-backend/src/test/js/app.test.cjs services/dashboard-backend/src/test/js/loading.test.cjs
```

React tsc/Vite build aprobado; salida nueva en
services/dashboard-frontend/target/phase9e-dist. Reactor en target/phase9e-reactor.log.
Selecciones focalizadas iniciales guardas/pipeline también aprobadas.

Omitidas por tag siete clases externas: las cinco PostgreSQL/brokers de estado y
las dos PostgreSQL del dashboard, enumeradas en la guía. No forman parte del
contador ni de skipped; no se ocultaron fallos de conexión como tests aprobados.
No se ejecutan Docker, migraciones, pruebas de permisos, integración Kafka/Rabbit,
caídas, restauración real o Playwright. El perfil externo no se activó aquí.

## 9. Regresiones 9A–9D

Sin regresiones detectadas en suite local: se preservan las correcciones previas
H01-H06 y M01/M02/M03/M06/M07/M09. Siguen pasando idempotencia, payloads/referencias, custodia/replay,
outbox, locks, historiales largos, snapshots de época, consistencia MVCC,
escritura opt-in, timeouts y recuperación UI. No se modifican migraciones de 9D,
época, lógica VAR, coordinador outbox ni configuración de infraestructura.
Los cambios de clases de integración son selección/seguridad de entorno, no
alteración de sus escenarios. Las garantías reales siguen sujetas a las pruebas
externas pendientes; M04/M08 no se declaran cerrados por validación de servidor.

## 10. Maven repackage

Los tres package offline con isolated-package terminaron BUILD SUCCESS y
spring-boot:3.5.7:repackage. Solo en esta etapa de empaquetado se usó -DskipTests
después del reactor completo; no se contabiliza como otra ejecución de tests.
Se inspeccionaron launcher JarLauncher, manifest con Start-Class, BOOT-INF/classes
y BOOT-INF/lib. No se ejecutaron los JAR contra infraestructura.

| Servicio | Clases aplicación | JAR dependencias |
|---|---:|---:|
| Productor | 9 | 48 |
| Estado | 41 | 44 |
| Dashboard | 39 | 59 |

Directorios nuevos bajo target/:

```text
phase9e-final-package-live-feed-producer-ee7fe425061c48ab9177f92d63d65271
phase9e-final-package-match-state-service-f473302b2a2c4ad6907d27756d526c59
phase9e-final-package-dashboard-backend-4b57c6b37a814ed4b6066f545ff0fc85
```

En cada uno se conservan JAR ejecutable y .jar.original nuevo. Logs de package
en services/<servicio>/target/phase9e-final-package.log. La pasada inicial de package
y sus logs también se conservan; la final incorpora los nombres/descripciones
Maven actualizados sin repetir las suites ya aprobadas. Los tres JAR anteriores en sus
target originales mantienen exactamente sus SHA-256 previos. No se reprodujo
bloqueo Windows 32 al probar apertura exclusiva de solo lectura; no se borró,
renombró o sobrescribió ninguno de ellos ni se detuvo un proceso para liberar acceso.

## 11. Riesgos y decisiones pendientes

- Aprobar schemas/versionado de equipo; los cuatro archivos siguen vacíos y no
  hay validación JSON Schema ni contratos LiveAlert/BettingCommand inventados.
- Aprobar/provisionar roles y privilegios con DEV 3 y probarlos en entorno aislado.
  El MVP actual continúa con sus credenciales/permisos previos, sin endurecimiento
  ficticio de servidor ni nueva dependencia de un rol aún inexistente.
- Integración externa necesita entorno desechable autorizado. Nombre/prefijo,
  flags y loopback son guardas adicionales, no prueba de propiedad de un servidor.
- CI remoto, política de pin SHA y eventuales controles de publicación quedan
  pendientes. Ningún build local prueba que el workflow se haya ejecutado en GitHub.
- Se conservan límites de 9D: STOMP singleton, entrega al menos una vez con
  duplicados posibles ante particiones, coste de historia y rotación administrativa.

## 12. git diff --check

git diff --check aprobado; se revisan además archivos nuevos con no-index contra
/dev/null. No-index devuelve 1 si hay diferencias con vacío: se valida ausencia
de diagnósticos, sin confundir ese valor con un error de whitespace. La revisión
final comprueba también inventario, respaldo, HEAD/rama y ausencia de staging.

## 13. Propuesta de commits locales (NO ejecutada)

Separar primero el acumulado por etapa/responsabilidad con hunks revisados;
no usar git add . ni mezclar cambios de varias etapas por archivo compartido:

1. 9A: compatibilidad, validación y recuperación de mensajería.
2. 9B: identidad/idempotencia de operaciones y escritura segura.
3. 9C: lectura consistente y recuperación UI; configuración/timeouts HTTP.
4. 9D: coordinación outbox; replay/historial acotado; época/migración y runbook.
5. 9E M04: propuesta de contratos y ampliación de compatibilidad wire.
6. 9E M05: tags, guardas, credenciales TEST y guía de integración.
7. 9E M08: propuesta de roles y matriz de permisos (documentación solamente).
8. 9E L01: reactor explícito, salida de package aislada, workflow y tests de política.
9. 9E L02: textos/comentarios vigentes e informe final.

Los POM contienen M05 y L01 y requieren selección de fragmentos coherente; los
README e informes deben acompañar la etapa correspondiente. Validar cada árbol
propuesto antes de solicitar autorización de commit. El respaldo pre9E permite
comparar/restaurar selectivamente si se autoriza; no se ha aplicado ni revertido.

Trabajo detenido al finalizar 9E. **Esperar autorización para 9F.**
