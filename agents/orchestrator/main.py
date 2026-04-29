#!/usr/bin/env python3
"""
Orchestrator Agent 最小可运行版。

职责：
1. 调用 technical agent 获取策略分析。
2. 调用 harness 质检接口获取可执行反馈。
3. 汇总成一个结构化决策包。
"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
import sys
from typing import Any

CURRENT_DIR = Path(__file__).resolve().parent
AGENTS_DIR = CURRENT_DIR.parent
ROOT_DIR = AGENTS_DIR.parent
sys.path.insert(0, str(AGENTS_DIR / "technical"))
sys.path.insert(0, str(ROOT_DIR / "harness" / "validators"))

from main import TechnicalAnalystAgent, TechnicalRequest  # type: ignore  # noqa: E402
from quality_check import QualityCheckConfig, run_quality_check  # type: ignore  # noqa: E402


class OrchestratorAgent:
    """主控 Agent：把技术分析和质检反馈合并。"""

    async def coordinate(self, request: TechnicalRequest) -> dict[str, Any]:
        technical_agent = TechnicalAnalystAgent(request.base_url)
        technical_result = await technical_agent.analyze(request)

        quality_result = await asyncio.to_thread(
            run_quality_check,
            QualityCheckConfig(
                base_url=request.base_url,
                stock_code=request.stock_code,
                start_date=request.start_date,
                end_date=request.end_date,
                fast_window=request.fast_window,
                slow_window=request.slow_window,
                initial_cash=100000.0,
                max_position_ratio=0.8,
                stop_loss_ratio=0.08,
            ),
        )

        return {
            "stockCode": request.stock_code,
            "technical": technical_result,
            "quality": quality_result["quality"],
            "decision": self.build_decision(technical_result, quality_result),
        }

    def build_decision(self, technical_result: dict[str, Any], quality_result: dict[str, Any]) -> dict[str, Any]:
        quality_passed = quality_result.get("status") == "passed"
        latest_signal = technical_result.get("latestSignal") or {}
        signal_type = latest_signal.get("type", "HOLD")

        if quality_passed and signal_type == "BUY":
            action = "WATCH_BUY_SIGNAL"
        elif quality_passed and signal_type == "SELL":
            action = "WATCH_SELL_SIGNAL"
        elif quality_passed:
            action = "HOLD"
        else:
            action = "REVIEW_PARAMETERS"

        return {
            "action": action,
            "reason": technical_result.get("summary"),
            "suggestions": quality_result.get("quality", {}).get("suggestions", []),
        }


def parse_args() -> TechnicalRequest:
    parser = argparse.ArgumentParser(description="Run orchestrator over Reference MVP")
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
    orchestrator = OrchestratorAgent()
    try:
        result = await orchestrator.coordinate(request)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except RuntimeError as exc:
        print(json.dumps({"status": "error", "message": str(exc)}, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
