#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose-mac.yml}"

cd "$ROOT_DIR"

docker compose -f "$COMPOSE_FILE" stop postgres clickhouse
