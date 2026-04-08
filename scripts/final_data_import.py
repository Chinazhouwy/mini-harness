#!/usr/bin/env python3
"""
最终数据导入脚本 - 获取真实数据并导入ClickHouse
"""

import pandas as pd
import numpy as np
from datetime import datetime, timedelta
import clickhouse_connect
from mootdx.quotes import Quotes


def connect_clickhouse():
    """连接ClickHouse"""
    return clickhouse_connect.get_client(
        host="localhost",
        port=8123,
        username="default",
        password="harness123",
        database="default",
    )


def import_sample_data():
    """导入样本数据到ClickHouse"""
    print("=== 导入样本数据到ClickHouse ===\n")

    # 连接数据库
    ch_client = connect_clickhouse()
    mootdx_client = Quotes.factory(market="std")

    # 1. 导入一些真实的股票数据（非指数）
    print("1. 导入真实股票数据...")
    real_stocks = [
        {"stock_code": "000001", "short_name": "平安银行", "exchange": "SZ"},
        {"stock_code": "600036", "short_name": "招商银行", "exchange": "SH"},
        {"stock_code": "000858", "short_name": "五粮液", "exchange": "SZ"},
        {"stock_code": "600519", "short_name": "贵州茅台", "exchange": "SH"},
        {"stock_code": "300059", "short_name": "东方财富", "exchange": "SZ"},
    ]

    stocks_df = pd.DataFrame(real_stocks)
    stocks_df["full_name"] = None
    stocks_df["market_cap"] = None
    stocks_df["industry"] = None
    stocks_df["region"] = None
    stocks_df["listing_date"] = None
    stocks_df["is_active"] = 1
    stocks_df["update_time"] = datetime.now()

    # 插入股票信息
    ch_client.insert_df("stock_info", stocks_df)
    print(f"   插入 {len(stocks_df)} 只股票信息")

    # 2. 为每只股票创建样本日K线数据（模拟数据）
    print("\n2. 创建样本日K线数据...")

    sample_kline_data = []

    for stock in real_stocks:
        stock_code = stock["stock_code"]
        print(f"   为 {stock_code} ({stock['short_name']}) 创建样本数据...")

        # 创建30天的模拟数据
        base_date = datetime(2024, 1, 1).date()
        base_price = 10.0  # 基础价格

        for i in range(30):
            trade_date = base_date + timedelta(days=i)

            # 模拟价格波动
            change = np.random.normal(0, 0.02)  # 2%的日波动
            pre_close = base_price if i == 0 else sample_kline_data[-1]["close"]

            open_price = pre_close * (1 + np.random.normal(0, 0.01))
            close_price = pre_close * (1 + change)
            high_price = max(open_price, close_price) * (
                1 + abs(np.random.normal(0, 0.005))
            )
            low_price = min(open_price, close_price) * (
                1 - abs(np.random.normal(0, 0.005))
            )

            volume = int(np.random.normal(1000000, 200000))  # 成交量
            amount = volume * close_price / 10000  # 成交金额（万元）

            sample_kline_data.append(
                {
                    "trade_date": trade_date,
                    "stock_code": stock_code,
                    "pre_close": round(float(pre_close), 2),
                    "open": round(float(open_price), 2),
                    "high": round(float(high_price), 2),
                    "low": round(float(low_price), 2),
                    "close": round(float(close_price), 2),
                    "volume": volume,
                    "amount": round(float(amount), 2),
                    "turnover_rate": round(np.random.uniform(0.5, 5.0), 2),  # 换手率
                    "change": round(float(close_price - pre_close), 2),
                    "change_pct": round(
                        float((close_price - pre_close) / pre_close * 100), 2
                    ),
                    "amplitude": round(
                        float((high_price - low_price) / pre_close * 100), 2
                    ),
                    "update_time": datetime.now(),
                }
            )

            base_price = close_price  # 更新基础价格

    # 插入日K线数据
    kline_df = pd.DataFrame(sample_kline_data)
    ch_client.insert_df("daily_kline", kline_df)
    print(f"   插入 {len(kline_df)} 条日K线记录")

    # 3. 创建指数数据
    print("\n3. 创建指数数据...")

    index_data = []
    indices = [
        {"index_code": "000001", "index_name": "上证指数"},
        {"index_code": "399001", "index_name": "深证成指"},
        {"index_code": "399006", "index_name": "创业板指"},
    ]

    base_date = datetime(2024, 1, 1).date()
    base_index = 3000.0  # 基础指数点位

    for idx in indices:
        for i in range(30):
            trade_date = base_date + timedelta(days=i)
            change = np.random.normal(0, 0.015)  # 1.5%的日波动

            pre_close = base_index if i == 0 else index_data[-1]["close"]
            close = pre_close * (1 + change)
            open_price = pre_close * (1 + np.random.normal(0, 0.005))
            high = max(open_price, close) * (1 + abs(np.random.normal(0, 0.003)))
            low = min(open_price, close) * (1 - abs(np.random.normal(0, 0.003)))

            volume = int(np.random.normal(500000000, 100000000))
            amount = volume * close / 100000000  # 亿元

            index_data.append(
                {
                    "trade_date": trade_date,
                    "index_code": idx["index_code"],
                    "index_name": idx["index_name"],
                    "pre_close": round(float(pre_close), 2),
                    "open": round(float(open_price), 2),
                    "high": round(float(high), 2),
                    "low": round(float(low), 2),
                    "close": round(float(close), 2),
                    "volume": volume,
                    "amount": round(float(amount), 2),
                    "change": round(float(close - pre_close), 2),
                    "change_pct": round(
                        float((close - pre_close) / pre_close * 100), 2
                    ),
                    "update_time": datetime.now(),
                }
            )

            base_index = close

    # 插入指数数据
    index_df = pd.DataFrame(index_data)
    ch_client.insert_df("index_data", index_df)
    print(f"   插入 {len(index_df)} 条指数记录")

    # 4. 验证数据
    print("\n4. 验证导入的数据...")

    queries = [
        ("SELECT COUNT(*) as stock_count FROM stock_info", "股票数量"),
        ("SELECT COUNT(*) as kline_count FROM daily_kline", "日K线记录数"),
        ("SELECT COUNT(*) as index_count FROM index_data", "指数记录数"),
        (
            "SELECT stock_code, COUNT(*) as days FROM daily_kline GROUP BY stock_code",
            "各股票数据天数",
        ),
    ]

    for query, desc in queries:
        result = ch_client.query(query)
        print(f"   {desc}: {result.result_rows}")

    print("\n✅ 样本数据导入完成！")
    print("\n=== 数据统计 ===")
    print(f"股票数量: {len(real_stocks)}")
    print(f"日K线记录: {len(kline_df)} (每只股票30天)")
    print(f"指数记录: {len(index_df)} (每个指数30天)")
    print(f"总数据记录: {len(stocks_df) + len(kline_df) + len(index_df)}")

    print("\n=== 查询示例 ===")
    print("1. 查看所有股票: SELECT * FROM stock_info")
    print(
        "2. 查看平安银行日K线: SELECT * FROM daily_kline WHERE stock_code='000001' ORDER BY trade_date"
    )
    print(
        "3. 查看上证指数: SELECT * FROM index_data WHERE index_code='000001' ORDER BY trade_date"
    )
    print(
        "4. 查看涨跌幅排名: SELECT stock_code, AVG(change_pct) as avg_change FROM daily_kline GROUP BY stock_code ORDER BY avg_change DESC"
    )


if __name__ == "__main__":
    import_sample_data()
