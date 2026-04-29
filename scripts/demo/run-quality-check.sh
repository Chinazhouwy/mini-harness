#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8082}"

cd "$ROOT_DIR"

if [[ "${TASK_ID:-}" != "" ]]; then
  python3 harness/validators/quality_check.py --base-url "$BASE_URL" --task-id "$TASK_ID"
else
  python3 harness/validators/quality_check.py \
    --base-url "$BASE_URL" \
    --stock-code "${STOCK_CODE:-000001}" \
    --start-date "${START_DATE:-2024-01-01}" \
    --end-date "${END_DATE:-2024-12-31}"
fi
