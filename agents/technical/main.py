#!/usr/bin/env python3
"""
Technical Agent 最小可运行版。

它现在不直接连 ClickHouse，而是通过 strategy-service Reference API 获取
策略信号和订单结果。这样 Agent 层先成为“解释与编排层”，不会绕开 Java
主链路。
"""

from __future__ import annotations

import argparse
import asyncio
import json
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class TechnicalRequest:
    base_url: str
    stock_code: str
    start_date: str
    end_date: str
    fast_window: int
    slow_window: int


class TechnicalAnalystAgent:
    """技术分析 Agent：调用 Java 策略服务并生成摘要。"""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    async def analyze(self, request: TechnicalRequest) -> dict[str, Any]:
        """执行技术分析。"""
        query = urlencode(
            {
                "stockCode": request.stock_code,
                "startDate": request.start_date,
                "endDate": request.end_date,
                "fastWindow": request.fast_window,
                "slowWindow": request.slow_window,
            }
        )
        response = await asyncio.to_thread(
            get_json,
            f"{self.base_url}/api/reference/backtest/quick?{query}",
        )

        report = response.get("report") or {}
        metrics = report.get("metrics") or {}
        signals = report.get("signals") or []
        orders = report.get("orders") or []
        latest_signal = signals[-1] if signals else None

        return {
            "taskId": response.get("taskId"),
            "stockCode": request.stock_code,
            "summary": build_summary(metrics, latest_signal, len(orders)),
            "metrics": metrics,
            "latestSignal": latest_signal,
            "orderCount": len(orders),
        }


def get_json(url: str) -> dict[str, Any]:
    try:
        with urlopen(Request(url, method="GET"), timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code}: {exc.read().decode('utf-8')}") from exc
    except URLError as exc:
        raise RuntimeError(f"Cannot connect to strategy-service: {exc.reason}") from exc


def build_summary(metrics: dict[str, Any], latest_signal: dict[str, Any] | None, order_count: int) -> str:
    total_return = float(metrics.get("totalReturn") or 0)
    max_drawdown = float(metrics.get("maxDrawdown") or 0)
    signal_text = "无信号" if latest_signal is None else f"{latest_signal.get('type')} @ {latest_signal.get('tradeDate')}"
    return (
        f"总收益 {total_return:.2%}，最大回撤 {max_drawdown:.2%}，"
        f"模拟订单 {order_count} 笔，最新信号：{signal_text}"
    )


def parse_args() -> TechnicalRequest:
    parser = argparse.ArgumentParser(description="Run technical analysis through strategy-service")
    parser.add_argument("--base-url", default="http://localhost:8082")
    parser.add_argument("--stock-code", default="000001")
    parser.add_argument("--start-date", default="2024-01-01")
    parser.add_argument("--end-date", default="2024-12-31")
    parser.add_argument("--fast-window", type=int, default=5)
    parser.add_argument("--slow-window", type=int, default=20)
    args = parser.parse_args()
    return TechnicalRequest(**vars(args))


async def main() -> int:
    request = parse_args()
    agent = TechnicalAnalystAgent(request.base_url)
    try:
        result = await agent.analyze(request)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except RuntimeError as exc:
        print(json.dumps({"status": "error", "message": str(exc)}, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
