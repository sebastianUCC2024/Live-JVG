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

## Ejecución

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
