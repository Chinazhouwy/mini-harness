#!/usr/bin/env python3
"""
Reference MVP 质检脚本。

职责：
1. 调用 strategy-service 的 Reference 回测 API。
2. 再调用对应的质量检查 API。
3. 输出固定 JSON，方便后续日报、Agent 或 CI 读取。

示例：
    python harness/validators/quality_check.py \
      --base-url http://localhost:8082 \
      --stock-code 000001 \
      --start-date 2024-01-01 \
      --end-date 2024-12-31
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class QualityCheckConfig:
    """质检输入参数。"""

    base_url: str
    stock_code: str
    start_date: str
    end_date: str
    fast_window: int
    slow_window: int
    initial_cash: float
    max_position_ratio: float
    stop_loss_ratio: float
    task_id: str | None
    output: str | None
    markdown: str | None


def post_json(url: str, payload: dict[str, Any]) -> dict[str, Any]:
    """发送 JSON POST 请求。"""
    body = json.dumps(payload).encode("utf-8")
    request = Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    return request_json(request)


def get_json(url: str) -> dict[str, Any]:
    """发送 JSON GET 请求。"""
    return request_json(Request(url, method="GET"))


def request_json(request: Request) -> dict[str, Any]:
    """执行 HTTP 请求并解析 JSON。"""
    try:
        with urlopen(request, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        detail = exc.read().decode("utf-8")
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise RuntimeError(f"Cannot connect to strategy-service: {exc.reason}") from exc


def run_quality_check(config: QualityCheckConfig) -> dict[str, Any]:
    """执行端到端质检。"""
    if config.task_id:
        task = get_json(f"{config.base_url}/api/reference/tasks/{config.task_id}")
        quality_report = get_json(f"{config.base_url}/api/reference/tasks/{config.task_id}/quality")
        return {
            "status": "passed" if quality_report.get("passed") else "failed",
            "taskId": config.task_id,
            "backtest": task.get("report"),
            "quality": quality_report,
        }

    payload = {
        "stockCode": config.stock_code,
        "startDate": config.start_date,
        "endDate": config.end_date,
        "fastWindow": config.fast_window,
        "slowWindow": config.slow_window,
        "initialCash": config.initial_cash,
        "maxPositionRatio": config.max_position_ratio,
        "stopLossRatio": config.stop_loss_ratio,
    }

    run_result = post_json(f"{config.base_url}/api/reference/backtest", payload)
    task_id = run_result.get("taskId")
    if not task_id:
        raise RuntimeError(f"Backtest did not return taskId: {run_result}")

    quality_report = get_json(f"{config.base_url}/api/reference/quality/{task_id}")
    return {
        "status": "passed" if quality_report.get("passed") else "failed",
        "taskId": task_id,
        "backtest": run_result.get("report"),
        "quality": quality_report,
    }


def parse_args() -> QualityCheckConfig:
    parser = argparse.ArgumentParser(description="Run Reference MVP quality check")
    parser.add_argument("--base-url", default="http://localhost:8082")
    parser.add_argument("--stock-code", default="000001")
    parser.add_argument("--start-date", default="2024-01-01")
    parser.add_argument("--end-date", default="2024-12-31")
    parser.add_argument("--fast-window", type=int, default=5)
    parser.add_argument("--slow-window", type=int, default=20)
    parser.add_argument("--initial-cash", type=float, default=100000.0)
    parser.add_argument("--max-position-ratio", type=float, default=0.8)
    parser.add_argument("--stop-loss-ratio", type=float, default=0.08)
    parser.add_argument("--task-id", default=None, help="复查已有回测任务，不触发新回测")
    parser.add_argument("--output", default=None, help="把 JSON 报告写入指定文件")
    parser.add_argument("--markdown", default=None, help="把 Markdown 报告写入指定文件")
    args = parser.parse_args()
    return QualityCheckConfig(**vars(args))


def write_reports(result: dict[str, Any], config: QualityCheckConfig) -> None:
    if config.output:
        Path(config.output).write_text(
            json.dumps(result, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    if config.markdown:
        quality = result.get("quality") or {}
        lines = [
            "# Reference MVP Quality Report",
            "",
            f"- Status: `{result.get('status')}`",
            f"- Task ID: `{result.get('taskId')}`",
            "",
            "## Checks",
            "",
        ]
        for check in quality.get("checks", []):
            marker = "PASS" if check.get("passed") else "FAIL"
            lines.append(f"- `{marker}` {check.get('name')}: {check.get('message')}")
        lines.extend(["", "## Suggestions", ""])
        for suggestion in quality.get("suggestions", []):
            lines.append(f"- {suggestion}")
        Path(config.markdown).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    config = parse_args()
    try:
        result = run_quality_check(config)
        write_reports(result, config)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result["status"] == "passed" else 2
    except RuntimeError as exc:
        print(json.dumps({"status": "error", "message": str(exc)}, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
