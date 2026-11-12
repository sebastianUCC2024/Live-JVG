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

La configuración de ejecución se completará
cuando los microservicios estén implementados.