# Live Sports Platform — VAR Enabled

Plataforma distribuida de marcadores deportivos en vivo,
con procesamiento de eventos y correcciones VAR.

## Tecnologías

- Java 17
- Spring Boot
- Apache Kafka
- RabbitMQ
- PostgreSQL
- React + TypeScript
- Docker Compose

## Arquitectura

Microservicios orientados a eventos con arquitectura hexagonal.

## Equipo de desarrollo

### DEV 1 — Core y VAR
- live-feed-producer
- match-state-service

### DEV 2 — Dashboard
- dashboard-backend
- notification-backend
- frontend

### DEV 3 — Persistencia e infraestructura
- historical-archiver
- betting-suspension-service
- betting-worker
- Docker Compose

## Validación local y CI (auditoría 9E)

`mvn test` o `mvn verify` en la raíz **sin perfil** solo procesa el POM raíz.
Para ejecutar el subconjunto de tres servicios del perfil, sin infraestructura externa:

```powershell
mvn -Paudit-services test
npm.cmd test --prefix services/dashboard-frontend
node --test services/dashboard-backend/src/test/js/operations.test.cjs services/dashboard-backend/src/test/js/app.test.cjs services/dashboard-backend/src/test/js/loading.test.cjs
```

GitHub Actions ejecuta verify por cada servicio, pruebas/build React y scripts
Node. No inicia brokers ni contenedores, no publica imágenes y no despliega.
La [guía de clon limpio](docs/CLEAN_CLONE.md) documenta instalación, validación, modos OFF/ON y provisión separada. Los cuatro JSON Schema están implementados en [contracts/events](contracts/events). La validación estructural no sustituye las invariantes de dominio ni certifica E2E.

## Arranque y seguridad

El Compose base inicia infraestructura y aplicaciones activas; no equivale a un arranque pasivo. No ejecutarlo sobre laboratorios conservados. El script existente `bash scripts/start-mvp.sh` exige `MVP_DB_PASSWORD` y `MVP_RABBIT_PASSWORD` definidos previamente y usa el proyecto fijo `live-jvg-dev1-isolated`; no genera credenciales ni garantiza un RUN nuevo. Revisar puertos, proyecto y recursos antes de autorizar su ejecución.

Para preparar un entorno aislado nuevo, seguir [la guía de reproducción](docs/CLEAN_CLONE.md). Las migraciones y la provisión requieren un paso separado. React se ejecuta aparte mediante npm; el puerto 18080 del Compose pertenece al Dashboard backend. No existe `scripts/check-mvp.py` en esta revisión.

Los [informes históricos de integración](docs/integration/local-academic-v1/report.md) conservan alcance, resultados y limitaciones de sus revisiones; no representan una nueva certificación del clon actual.
