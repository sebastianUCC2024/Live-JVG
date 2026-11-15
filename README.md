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
Para ejecutar los tres servicios implementados, sin infraestructura externa:

```powershell
mvn -Paudit-services test
npm.cmd test --prefix services/dashboard-frontend
node --test services/dashboard-backend/src/test/js/operations.test.cjs services/dashboard-backend/src/test/js/app.test.cjs services/dashboard-backend/src/test/js/loading.test.cjs
```

GitHub Actions ejecuta verify por cada servicio, pruebas/build React y scripts
Node. No inicia brokers ni contenedores, no publica imágenes y no despliega.
La [guía de integración durable](docs/PRUEBAS_INTEGRACION_DURABLE.md) explica las
pruebas externas excluidas y su autorización separada. Los cuatro schemas están
vacíos: véase la [propuesta de contratos](docs/CONTRATOS_VERSIONADOS_PROPUESTA.md),
pendiente de aprobación del equipo. Los [permisos PostgreSQL propuestos](docs/POSTGRES_MINIMO_PRIVILEGIO_PROPUESTA.md)
no se han aplicado a ningún servidor.

## Ejecución del MVP (inicia infraestructura)

Consulta los [modos de ejecución y su configuración](docs/MODOS_EJECUCION.md)
para distinguir Compose, procesos en el host y el perfil histórico `memory`.

El MVP local conecta simulador REST → Kafka → estado durable → RabbitMQ → dashboard web.

```sh
./scripts/start-mvp.sh
```

Interfaz: http://localhost:18080. El script compila los tres módulos esenciales,
genera credenciales locales en `.mvp/local.env` y arranca un proyecto Docker propio
sin borrar volúmenes. Requiere Java 17, Maven, Docker Compose y Python 3.

Comprobación sin publicar eventos: `python3 scripts/check-mvp.py`.
Demostración automatizada con cinco eventos nuevos: `python3 scripts/check-mvp.py --publish`.

Las credenciales y el identificador de partido se conservan en `.mvp/`, ignorado
por Git. No elimines ese archivo si quieres reutilizar los volúmenes existentes.
Este MVP es para uso local; los cuatro servicios secundarios no se incluyen en
el arranque. Consulta el [informe final y guion de demostración](docs/live-jvg-mvp-final-report.md).
