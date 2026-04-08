#!/usr/bin/env python3
"""
设置ClickHouse表结构
"""

import subprocess
import sys
from pathlib import Path


def execute_sql(sql_command, description=""):
    """执行单个SQL命令"""
    if description:
        print(f"执行: {description}")

    # 构建curl命令
    cmd = [
        "curl",
        "-s",
        "http://localhost:8123/",
        "--user",
        "default:harness123",
        "--data-binary",
        sql_command,
    ]

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if result.returncode == 0:
            if result.stdout.strip():
                print(f"  结果: {result.stdout.strip()}")
            else:
                print("  成功")
            return True
        else:
            print(f"  失败: {result.stderr}")
            return False
    except subprocess.TimeoutExpired:
        print("  超时")
        return False
    except Exception as e:
        print(f"  错误: {e}")
        return False


def main():
    print("=== 设置ClickHouse表结构 ===\n")

    # 使用harness_db数据库
    if not execute_sql("USE harness_db", "使用harness_db数据库"):
        print("请先创建harness_db数据库")
        sys.exit(1)

    # 逐个创建表
    tables = [
        (
            """CREATE TABLE IF NOT EXISTS stock_info
(
    stock_code String,
    short_name String,
    full_name Nullable(String),
    exchange String,
    market_cap Nullable(Decimal64(4)),
    industry Nullable(String),
    region Nullable(String),
    listing_date Nullable(Date),
    is_active UInt8 DEFAULT 1,
    update_time DateTime DEFAULT now(),
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1,
    INDEX idx_exchange exchange TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (exchange, stock_code)
PARTITION BY exchange""",
            "创建股票基本信息表",
        ),
        (
            """CREATE TABLE IF NOT EXISTS daily_kline
(
    trade_date Date,
    stock_code String,
    pre_close Decimal64(4),
    open Decimal64(4),
    high Decimal64(4),
    low Decimal64(4),
    close Decimal64(4),
    volume UInt64,
    amount Decimal64(4),
    turnover_rate Nullable(Decimal64(4)),
    change Decimal64(4),
    change_pct Decimal64(4),
    amplitude Decimal64(4),
    update_time DateTime DEFAULT now(),
    INDEX idx_trade_date trade_date TYPE minmax GRANULARITY 1,
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1,
    INDEX idx_date_code (trade_date, stock_code) TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (stock_code, trade_date)
PARTITION BY toYYYYMM(trade_date)
TTL trade_date + INTERVAL 5 YEAR""",
            "创建日K线行情表",
        ),
        (
            """CREATE TABLE IF NOT EXISTS minute_kline
(
    trade_time DateTime,
    stock_code String,
    interval_type UInt8,
    open Decimal64(4),
    high Decimal64(4),
    low Decimal64(4),
    close Decimal64(4),
    volume UInt64,
    amount Decimal64(4),
    update_time DateTime DEFAULT now(),
    INDEX idx_trade_time trade_time TYPE minmax GRANULARITY 1,
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1,
    INDEX idx_interval interval_type TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (stock_code, interval_type, trade_time)
PARTITION BY toYYYYMM(trade_time)
TTL trade_time + INTERVAL 1 YEAR""",
            "创建分钟K线行情表",
        ),
        (
            """CREATE TABLE IF NOT EXISTS financial_data
(
    report_date Date,
    stock_code String,
    report_type String,
    total_revenue Nullable(Decimal64(4)),
    net_profit Nullable(Decimal64(4)),
    eps Nullable(Decimal64(4)),
    roe Nullable(Decimal64(4)),
    total_assets Nullable(Decimal64(4)),
    total_liabilities Nullable(Decimal64(4)),
    operating_cash_flow Nullable(Decimal64(4)),
    pe_ratio Nullable(Decimal64(4)),
    pb_ratio Nullable(Decimal64(4)),
    update_time DateTime DEFAULT now(),
    INDEX idx_report_date report_date TYPE minmax GRANULARITY 1,
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1,
    INDEX idx_report_type report_type TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (stock_code, report_date, report_type)
PARTITION BY toYYYYMM(report_date)""",
            "创建财务数据表",
        ),
        (
            """CREATE TABLE IF NOT EXISTS index_data
(
    trade_date Date,
    index_code String,
    index_name String,
    pre_close Decimal64(4),
    open Decimal64(4),
    high Decimal64(4),
    low Decimal64(4),
    close Decimal64(4),
    volume UInt64,
    amount Decimal64(4),
    change Decimal64(4),
    change_pct Decimal64(4),
    update_time DateTime DEFAULT now(),
    INDEX idx_trade_date trade_date TYPE minmax GRANULARITY 1,
    INDEX idx_index_code index_code TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (index_code, trade_date)
PARTITION BY toYYYYMM(trade_date)""",
            "创建指数数据表",
        ),
    ]

    success_count = 0
    for sql, desc in tables:
        if execute_sql(sql, desc):
            success_count += 1

    print(f"\n=== 完成: {success_count}/{len(tables)} 个表创建成功 ===")

    # 验证表已创建
    print("\n验证表结构...")
    execute_sql("SHOW TABLES", "查看已创建的表")


if __name__ == "__main__":
    main()
