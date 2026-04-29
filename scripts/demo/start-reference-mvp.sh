#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-mac.yml}"

cd "$ROOT_DIR"

docker compose -f "$COMPOSE_FILE" up -d postgres clickhouse
docker exec -i harness-postgres psql -U harness -d harness_db < scripts/create_reference_postgres_tables.sql

cd services
REFERENCE_STORE_TYPE=jdbc \
REFERENCE_POSTGRES_URL="${REFERENCE_POSTGRES_URL:-jdbc:postgresql://localhost:5432/harness_db}" \
REFERENCE_POSTGRES_USERNAME="${REFERENCE_POSTGRES_USERNAME:-harness}" \
REFERENCE_POSTGRES_PASSWORD="${REFERENCE_POSTGRES_PASSWORD:-harness123}" \
mvn -pl strategy-service spring-boot:run
