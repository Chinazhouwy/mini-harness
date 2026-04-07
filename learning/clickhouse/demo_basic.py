#!/usr/bin/env python3
"""
ClickHouse 基础演示脚本

这个脚本演示了如何：
1. 连接 ClickHouse
2. 创建表
3. 插入数据
4. 查询数据
5. 与 pandas 集成
"""

import time
from clickhouse_driver import Client
import pandas as pd


def main():
    # 1. 连接 ClickHouse
    print("1. 连接到 ClickHouse...")
    client = Client(
        host="localhost", port=9000, database="agentic", user="default", password=""
    )

    # 2. 创建数据库和表
    print("2. 创建数据库和表...")
    client.execute("CREATE DATABASE IF NOT EXISTS agentic")
    client.execute("USE agentic")

    create_table_sql = """
    CREATE TABLE IF NOT EXISTS demo_kline (
        symbol String,
        date Date,
        open Float64,
        high Float64,
        low Float64,
        close Float64,
        volume UInt64
    ) ENGINE = MergeTree()
    ORDER BY (symbol, date)
    """
    client.execute(create_table_sql)

    # 3. 插入示例数据
    print("3. 插入示例数据...")
    sample_data = [
        ("AAPL", "2024-04-01", 170.0, 172.5, 169.8, 171.2, 50000000),
        ("AAPL", "2024-04-02", 171.2, 173.0, 170.5, 172.8, 48000000),
        ("AAPL", "2024-04-03", 172.8, 174.2, 172.0, 173.5, 52000000),
        ("GOOGL", "2024-04-01", 150.0, 152.3, 149.5, 151.8, 30000000),
        ("GOOGL", "2024-04-02", 151.8, 153.1, 151.0, 152.5, 28000000),
        ("GOOGL", "2024-04-03", 152.5, 154.0, 152.0, 153.2, 32000000),
    ]

    client.execute("INSERT INTO demo_kline VALUES", sample_data)

    # 4. 基本查询
    print("4. 执行基本查询...")
    result = client.execute("SELECT * FROM demo_kline ORDER BY symbol, date")
    for row in result:
        print(f"  {row}")

    # 5. 聚合查询
    print("5. 执行聚合查询...")
    agg_result = client.execute("""
        SELECT 
            symbol,
            count() as days,
            avg(close) as avg_close,
            max(high) as highest,
            min(low) as lowest
        FROM demo_kline 
        GROUP BY symbol
        ORDER BY symbol
    """)

    for row in agg_result:
        print(f"  {row}")

    # 6. 与 pandas 集成
    print("6. 与 pandas 集成...")
    df = pd.DataFrame(
        result, columns=["symbol", "date", "open", "high", "low", "close", "volume"]
    )
    print(f"  DataFrame shape: {df.shape}")
    print(f"  AAPL average close: {df[df['symbol'] == 'AAPL']['close'].mean():.2f}")

    # 7. 技术指标计算示例
    print("7. 计算简单移动平均线...")
    aapl_data = df[df["symbol"] == "AAPL"].copy()
    aapl_data = aapl_data.sort_values("date")
    aapl_data["ma3"] = aapl_data["close"].rolling(window=3).mean()
    print(aapl_data[["date", "close", "ma3"]])

    print("\n✅ ClickHouse 基础演示完成！")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"❌ 错误: {e}")
        print("请确保已经启动 ClickHouse: docker-compose up -d clickhouse")
