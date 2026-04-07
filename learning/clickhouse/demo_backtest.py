#!/usr/bin/env python3
"""
ClickHouse 回测演示脚本

这个脚本演示了如何使用 ClickHouse 进行量化回测：
1. 生成大量历史数据
2. 执行双均线策略回测
3. 让我们看看性能有多快！
"""

import random
import time
from datetime import datetime, timedelta
from clickhouse_driver import Client
import pandas as pd


def generate_mock_data(days=730, symbols=["000001", "000002", "600000"]):
    """生成模拟的股票历史数据"""
    print(f"生成 {len(symbols)} 只股票 {days} 天的数据...")

    data = []
    start_date = datetime.now() - timedelta(days=days)

    for symbol in symbols:
        price = random.uniform(10, 50)
        for i in range(days):
            date = start_date + timedelta(days=i)
            # 模拟价格波动
            change = random.uniform(-0.03, 0.03)
            price = price * (1 + change)
            high = price * random.uniform(1.01, 1.03)
            low = price * random.uniform(0.97, 0.99)
            open_price = random.uniform(low, high)
            close_price = price
            volume = random.randint(1000000, 10000000)

            data.append(
                (
                    symbol,
                    date.strftime("%Y-%m-%d"),
                    open_price,
                    high,
                    low,
                    close_price,
                    volume,
                )
            )

    return data


def create_backtest_table(client):
    """创建回测专用表"""
    client.execute("CREATE DATABASE IF NOT EXISTS agentic")
    client.execute("USE agentic")

    # 创建高性能的回测表
    create_sql = """
    CREATE TABLE IF NOT EXISTS backtest_kline (
        symbol String,
        date Date,
        open Float64,
        high Float64,
        low Float64,
        close Float64,
        volume UInt64
    ) ENGINE = MergeTree()
    PARTITION BY toYYYYMM(date)
    ORDER BY (symbol, date)
    SETTINGS index_granularity = 8192
    """
    client.execute(create_sql)


def run_dual_ma_backtest(client, symbol="000001"):
    """执行双均线回测策略"""
    print(f"\n执行 {symbol} 的双均线回测...")

    # 使用 ClickHouse 的窗口函数计算均线
    backtest_sql = f"""
    WITH moving_averages AS (
        SELECT
            date,
            close,
            -- 5日均线
            avg(close) OVER (
                ORDER BY date 
                ROWS BETWEEN 4 PRECEDING AND CURRENT ROW
            ) as ma5,
            -- 20日均线
            avg(close) OVER (
                ORDER BY date 
                ROWS BETWEEN 19 PRECEDING AND CURRENT ROW
            ) as ma20,
            -- 上一日的均线（用于金叉死叉判断）
            lag(avg(close) OVER (
                ORDER BY date 
                ROWS BETWEEN 4 PRECEDING AND CURRENT ROW
            ), 1) OVER (ORDER BY date) as prev_ma5,
            lag(avg(close) OVER (
                ORDER BY date 
                ROWS BETWEEN 19 PRECEDING AND CURRENT ROW
            ), 1) OVER (ORDER BY date) as prev_ma20
        FROM backtest_kline
        WHERE symbol = '{symbol}'
        ORDER BY date
    )
    SELECT
        date,
        close,
        ma5,
        ma20,
        CASE
            WHEN prev_ma5 <= prev_ma20 AND ma5 > ma20 THEN 'BUY'
            WHEN prev_ma5 >= prev_ma20 AND ma5 < ma20 THEN 'SELL'
            ELSE 'HOLD'
        END as signal
    FROM moving_averages
    WHERE date > '2023-01-01'  -- 跳过初始的均线计算期
    ORDER BY date
    """

    start_time = time.time()
    results = client.execute(backtest_sql)
    end_time = time.time()

    print(f"回测完成！耗时: {(end_time - start_time) * 1000:.2f}ms")
    print(f"总交易日: {len(results)}")

    # 统计信号
    signals = [row[4] for row in results]
    buy_count = signals.count("BUY")
    sell_count = signals.count("SELL")
    print(f"买入信号: {buy_count}, 卖出信号: {sell_count}")

    return results


def main():
    print("🚀 ClickHouse 回测性能演示")
    print("=" * 50)

    # 连接数据库
    client = Client(host="localhost", port=9000)

    # 创建表
    create_backtest_table(client)

    # 生成并插入大量数据（模拟真实场景）
    mock_data = generate_mock_data(
        days=730, symbols=["000001", "000002", "600000", "600001"]
    )
    print(f"插入 {len(mock_data)} 条记录到 ClickHouse...")

    insert_start = time.time()
    client.execute("INSERT INTO backtest_kline VALUES", mock_data)
    insert_end = time.time()
    print(f"插入完成！耗时: {(insert_end - insert_start) * 1000:.2f}ms")

    # 执行回测
    for symbol in ["000001", "000002"]:
        run_dual_ma_backtest(client, symbol)

    # 性能对比：查询大量数据
    print("\n📊 性能测试：查询所有数据")
    query_start = time.time()
    total_count = client.execute("SELECT count(*) FROM backtest_kline")[0][0]
    query_end = time.time()
    print(f"总记录数: {total_count}")
    print(f"查询耗时: {(query_end - query_start) * 1000:.2f}ms")

    print("\n✅ ClickHouse 回测演示完成！")
    print(f"这就是为什么我们的目标是 1.2秒/年 的回测速度！")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"❌ 错误: {e}")
        print("请确保已经启动 ClickHouse: docker-compose up -d clickhouse")
