# Reproducción desde un clon limpio

Esta guía no autoriza provisión, arranque ni E2E. No reutilizar laboratorios históricos.
Los informes anteriores son evidencia histórica, no resultados de un clon nuevo.

## Requisitos

- Git, Java 17 y Maven. El Dockerfile utiliza Maven 3.9.6; los wrappers declaran Maven 3.10.0 y requieren verificar acceso a su distribución. Invocar wrappers POSIX mediante `sh services/<módulo>/mvnw` porque no tienen permiso ejecutable en Git.
- Node >=22.13.0, conforme a package.json; CI utiliza Node 24. Usar el npm de esa instalación y `npm ci` con el lockfile versionado.
- Python 3.12 recomendado (CI), con venv y pip. No depender de entornos o cachés copiados desde otra máquina.
- Para una fase Docker posterior: Docker con BuildKit y Compose >=2.24.4 por las etiquetas `!override`/`!reset` de overlays.
- Acceso a Maven Central, registro npm, PyPI y, para imágenes, sus registros. La primera instalación no es offline.

## Instalación y comprobaciones sin infraestructura

Desde la raíz del clon, sin copiar `.mvp/`, `.env`, datos o credenciales de laboratorios:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-validation.txt
npm ci --prefix services/dashboard-frontend
```

Java 17 debe estar seleccionado; en macOS se puede usar `export JAVA_HOME="$(/usr/libexec/java_home -v 17)"` y `export PATH="$JAVA_HOME/bin:$PATH"`.
No exportar flags `DEV3_RUN_*`, aprobaciones de integración ni activar perfiles externos.
En PowerShell usar los equivalentes `.venv\Scripts\python.exe` y `npm.cmd`.

```sh
for module in live-feed-producer match-state-service dashboard-backend historical-archiver betting-suspension-service betting-worker notification-backend; do
  mvn -B -ntp -f "services/$module/pom.xml" verify || exit 1
done
npm test --prefix services/dashboard-frontend
npm run lint --prefix services/dashboard-frontend
npm run build --prefix services/dashboard-frontend
node --test services/dashboard-backend/src/test/js/*.test.cjs
```

El POM raíz sin perfil no prueba servicios; `audit-services` incluye solo Producer, Match State y Dashboard. No sustituye el bucle de siete módulos.
Las pruebas externas permanecen omitidas: registrar tests, fallos, errores y omisiones sin sumar reportes viejos.
Notification conserva su parent Spring Boot 4.1.2; los otros módulos deben evaluarse con sus POM reales, sin imponerles una versión común.

### Fixtures y contratos: orden obligatorio

La suite local Notification debe terminar correctamente antes de estos comandos: genera `target/live-alert-wire-fixtures.json` y `target/live-alert-rest-fixture.json`. No copiar fixtures de otro worktree ni versionar target.

```sh
.venv/bin/python scripts/validate-dev1-contracts.py
.venv/bin/python scripts/test-validate-dev1-contracts.py
.venv/bin/python scripts/test-published-contracts.py
.venv/bin/python scripts/validate-integrated-contracts.py
.venv/bin/python scripts/test-integrated-contracts.py
JVG_FRONTEND_NODE_MODULES="$(pwd)/services/dashboard-frontend/node_modules" \
  node services/notification-backend/scripts/verify-react-alert-contract.cjs
.venv/bin/python scripts/test-e2e-harness.py
.venv/bin/python scripts/test-academic-e2e.py
.venv/bin/python scripts/test-docker-runtime.py
.venv/bin/python scripts/test-clean-clone-docs.py
```

Estos tests verifican contratos y planificación offline, no entrega de brokers. Los cuatro schemas compartidos están en `contracts/events/`; el comando académico separado está en `services/betting-suspension-service/docs/academic-command-v1.schema.json`.

## Configuración y modos seguros

Copiar únicamente plantillas pertinentes, nunca credenciales históricas. La plantilla raíz `.env.example` contiene nombres de referencia: Spring Boot no la carga automáticamente. Cada servicio utiliza sus variables documentadas en sus application.properties y README. Variables `VITE_*` son públicas; no contienen secretos.

React: copiar `services/dashboard-frontend/.env.example` a `.env.local`, ajustar `VITE_DASHBOARD_ORIGIN` y opcionalmente `VITE_NOTIFICATION_ORIGIN` al entorno aprobado. Ejecutar `npm run dev --prefix services/dashboard-frontend`; escucha en 127.0.0.1:5173. `/?mode=demo` usa muestras identificadas; tiempo real necesita backend y CORS aprobados. No confundir demo con datos persistidos.

OFF pasivo exige configuración efectiva explícita, no solo un perfil: consumidores, simulador, publicaciones, RabbitAdmin, SQL init y Flyway deshabilitados. Revisar [arranque pasivo](../scripts/e2e/passive-startup-v1.md) y [preflight](../scripts/e2e/preflight-v1.md).
En ambas réplicas Worker: ACADEMIC_COMMANDS_ENABLED=false, ACADEMIC_PUBLICATION_ENABLED=false, ACADEMIC_CONSUMER_ENABLED=false, ACADEMIC_TOPOLOGY_ENABLED=false, BETTING_WORKER_CONSUMER_ENABLED=false, BETTING_WORKER_ACCEPTANCE_ENABLED=false y BETTING_WORKER_AUTHORIZATION_MODE=disabled. Los flags académicos productores también deben estar OFF en Suspension.

ON académico requiere autorización independiente, identidades exclusivas, topología preaprovisionada y solo los adaptadores deportivos/académicos necesarios. Aceptación, autoridad, grants y leases siguen deshabilitados. Ver [overlay propuesto ON](../scripts/e2e/compose.academic-proposed.yml); no ejecutarlo como si fuera configuración OFF.

## Provisión y migraciones separadas

No arrancar Compose base globalmente para conseguir un entorno pasivo. `scripts/start-mvp.sh` exige MVP_DB_PASSWORD y MVP_RABBIT_PASSWORD, usa un nombre fijo de proyecto y arranca servicios activos. No crea credenciales, no carga todas las migraciones y no garantiza aislamiento por RUN.

Un despliegue nuevo requiere inventario previo, RUN exclusivo, puertos loopback libres, volúmenes/red nuevos y credenciales fuera de Git. Preparar y revisar [propuesta común de PostgreSQL](../services/notification-backend/docs/common-v1-proposal/README.md) y [manifiesto propuesto](../scripts/e2e/live-jvg-e2e-5c84548cf006.proposed.json). El RUN de ejemplo no debe reutilizarse.
Aplicar las migraciones originales por servicio mediante Flyway separado, con owners/migradores distintos de runtime y Control antes de Worker; validar checksums, ownership y mínimo privilegio. No aplicar paquetes legacy de aceptación ni fixtures de grants/leases al ensayo académico.
Archiver JPA requiere V1/V2 ya aplicadas, ARCHIVER_STORAGE_ADAPTER=jpa, Hibernate validate y Flyway OFF. Consumidor inicialmente OFF; no usar Hibernate create/update. Ver [adaptador JPA](../services/historical-archiver/docs/jpa-adapter-validation.md).
Kafka topic y RabbitMQ vhosts/exchanges/colas/bindings/ACL deben provisionarse por separado. Un arranque OFF no los declara automáticamente. Cualquier activación necesita baseline y comprobación posterior de cero operaciones ACCEPTED, grants y leases.

## Certificación Docker futura, sin arrancar servicios

Solo después de autorizar builds e instalaciones. Desde un clon nuevo sin target ni configuración privada:

```sh
for module in live-feed-producer match-state-service dashboard-backend historical-archiver betting-suspension-service betting-worker notification-backend; do
  docker build --target runtime --build-arg "SERVICE_MODULE=$module" \
    -t "live-jvg-cleancheck/$module" . || exit 1
done
```

El target runtime compila y ejecuta verify en la etapa Maven, incluye migraciones del JAR y usa Java 17 JRE/no root. No utilizar el target prebuilt para demostrar un build limpio. Revisar contexto, imagen, usuario, secretos y reportes; no ejecutar Docker up ni E2E como parte del build. Esta limpieza documental no certifica ese build: debe ejecutarse en la fase posterior autorizada.

## Evidencias y límites

Conservar [resultados de integración](integration/local-academic-v1/report.md), [cierre de dependencias](integration/local-academic-v1/dependency-close/report.md) y [evidencia JPA compartible](../services/historical-archiver/docs/evidence/jpa/verification.json). Logs originales pueden contener rutas históricas; no son prerequisitos de instalación. Evidencias privadas y volúmenes permanecen fuera del clon.
ORDER02 global no queda resuelto por estas instrucciones; Kafka PLAINTEXT corresponde al laboratorio. No hay apuestas reales. Un build verde no certifica recuperación, ACL ni E2E real. No borrar evidencias ni recursos para obtener una validación limpia.
