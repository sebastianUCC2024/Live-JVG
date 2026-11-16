#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${MVP_DB_PASSWORD:?Set a local isolated database password}"
: "${MVP_RABBIT_PASSWORD:?Set a local isolated RabbitMQ password}"
# Never share the laboratory project or its volumes. No down/reset operations.
export COMPOSE_PROJECT_NAME=live-jvg-dev1-isolated
docker compose config --quiet
docker compose up --build -d "$@"
