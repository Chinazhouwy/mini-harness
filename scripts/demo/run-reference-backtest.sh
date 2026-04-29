#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8082}"
STOCK_CODE="${STOCK_CODE:-000001}"
START_DATE="${START_DATE:-2024-01-01}"
END_DATE="${END_DATE:-2024-12-31}"

curl -sS -X POST "$BASE_URL/api/reference/backtest" \
  -H "Content-Type: application/json" \
  -d "{
    \"stockCode\": \"$STOCK_CODE\",
    \"startDate\": \"$START_DATE\",
    \"endDate\": \"$END_DATE\",
    \"fastWindow\": 5,
    \"slowWindow\": 20,
    \"initialCash\": 100000,
    \"maxPositionRatio\": 0.8,
    \"stopLossRatio\": 0.08
  }"
