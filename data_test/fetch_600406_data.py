#!/usr/bin/env python3
"""
下载国电南瑞(600406)1年日K数据并存储到ClickHouse临时表
"""

import pandas as pd
import numpy as np
from datetime import datetime, timedelta
import clickhouse_connect
from mootdx.quotes import Quotes
import traceback


def main():
    print("=== 下载国电南瑞(600406)日K数据 ===")
    print(f"当前时间: {datetime.now()}")

    # 1. 初始化客户端
    print("\n1. 初始化连接...")
    try:
        # mootdx客户端
        tdx_client = Quotes.factory(market="std")
        print("✅ mootdx客户端初始化成功")

        # ClickHouse客户端
        ch_client = clickhouse_connect.get_client(
            host="localhost",
            port=8123,
            username="default",
            password="harness123",
            database="default",
        )
        print("✅ ClickHouse连接成功")
    except Exception as e:
        print(f"❌ 初始化失败: {e}")
        traceback.print_exc()
        return

    # 2. 创建临时表
    print("\n2. 创建临时表...")
    try:
        # 先删除已存在的临时表
        ch_client.command("DROP TABLE IF EXISTS temp_600406_daily_kline")

        # 创建临时表
        create_table_sql = """
        CREATE TABLE temp_600406_daily_kline
        (
            trade_date Date,
            stock_code String,
            stock_name String DEFAULT '国电南瑞',
            pre_close Decimal64(4),
            open Decimal64(4),
            high Decimal64(4),
            low Decimal64(4),
            close Decimal64(4),
            volume UInt64,     -- 成交量（股）
            amount Decimal64(4), -- 成交金额（万元）
            change Decimal64(4),  -- 涨跌额
            change_pct Decimal64(4), -- 涨跌幅(%)
            amplitude Decimal64(4), -- 振幅(%)
            fetch_time DateTime DEFAULT now()
        )
        ENGINE = MergeTree()
        ORDER BY trade_date
        """
        ch_client.command(create_table_sql)
        print("✅ 临时表 temp_600406_daily_kline 创建成功")
    except Exception as e:
        print(f"❌ 创建表失败: {e}")
        traceback.print_exc()
        return

    # 3. 获取日K数据
    print("\n3. 获取国电南瑞日K数据...")
    try:
        # 获取最近1年数据，大约250个交易日
        # mootdx的bars方法offset参数表示获取多少条数据
        kline_data = tdx_client.bars(
            symbol="600406",  # 国电南瑞股票代码
            frequency=9,  # 9=日K线
            offset=250,  # 获取最近250个交易日，大约1年
        )

        if kline_data is None or kline_data.empty:
            print("❌ 未获取到任何数据")
            return

        print(f"✅ 获取到 {len(kline_data)} 条日K记录")
        print(f"数据列名: {list(kline_data.columns)}")
        print(f"索引类型: {type(kline_data.index)}")
        print(f"前3条索引: {kline_data.index[:3]}")
        print(f"后3条索引: {kline_data.index[-3:]}")

    except Exception as e:
        print(f"❌ 获取数据失败: {e}")
        traceback.print_exc()
        return

    # 4. 数据清洗和转换
    print("\n4. 数据清洗和转换...")
    try:
        # 先处理重复索引
        df = kline_data[~kline_data.index.duplicated(keep="first")].copy()
        print(f"移除重复索引后剩余: {len(df)} 条记录")

        # 使用索引作为交易日期（更准确）
        df["trade_date"] = df.index.date

        # 数据中已经有volume列，使用vol字段（更准确的成交量数据）
        df["volume"] = df["vol"]

        # 确保trade_date是日期类型
        df["trade_date"] = pd.to_datetime(df["trade_date"]).dt.date

        # 计算衍生字段
        df["pre_close"] = df["close"].shift(1)
        df["change"] = df["close"] - df["pre_close"]
        df["change_pct"] = (df["change"] / df["pre_close"]) * 100
        df["amplitude"] = ((df["high"] - df["low"]) / df["pre_close"]) * 100
        df["amount"] = (df["volume"] * df["close"]) / 10000  # 转换为万元
        df["stock_code"] = "600406"

        # 删除第一条（没有pre_close）
        df = df.dropna(subset=["pre_close"])

        # 保留需要的列
        df = df[
            [
                "trade_date",
                "stock_code",
                "pre_close",
                "open",
                "high",
                "low",
                "close",
                "volume",
                "amount",
                "change",
                "change_pct",
                "amplitude",
            ]
        ]

        # 数据类型转换
        df["pre_close"] = df["pre_close"].astype(float).round(2)
        df["open"] = df["open"].astype(float).round(2)
        df["high"] = df["high"].astype(float).round(2)
        df["low"] = df["low"].astype(float).round(2)
        df["close"] = df["close"].astype(float).round(2)
        df["volume"] = df["volume"].astype(int)
        df["amount"] = df["amount"].astype(float).round(2)
        df["change"] = df["change"].astype(float).round(2)
        df["change_pct"] = df["change_pct"].astype(float).round(2)
        df["amplitude"] = df["amplitude"].astype(float).round(2)

        print(f"✅ 清洗后的数据行数: {len(df)}")
        print(f"日期范围: {df['trade_date'].min()} 到 {df['trade_date'].max()}")
        print(f"价格范围: {df['low'].min():.2f} - {df['high'].max():.2f}")
        print(f"总成交量: {df['volume'].sum():,} 股")
        print(f"总成交金额: {df['amount'].sum():,.2f} 万元")

    except Exception as e:
        print(f"❌ 数据清洗失败: {e}")
        traceback.print_exc()
        return

    # 5. 插入ClickHouse
    print("\n5. 插入数据到ClickHouse...")
    try:
        ch_client.insert_df(
            "temp_600406_daily_kline",
            df,
            settings={"async_insert": 0, "wait_for_async_insert": 1},
        )
        print(f"✅ 成功插入 {len(df)} 条记录")
    except Exception as e:
        print(f"❌ 插入失败: {e}")
        traceback.print_exc()
        return

    # 6. 验证数据
    print("\n6. 验证存储的数据...")
    try:
        # 统计数据
        stats = ch_client.query("""
        SELECT 
            COUNT(*) as total_rows,
            MIN(trade_date) as min_date,
            MAX(trade_date) as max_date,
            MIN(low) as min_price,
            MAX(high) as max_price,
            AVG(volume) as avg_volume,
            SUM(volume) as total_volume
        FROM temp_600406_daily_kline
        """)

        result = stats.result_rows[0]
        print(f"✅ 数据验证通过！")
        print(f"总记录数: {result[0]}")
        print(f"日期范围: {result[1]} 到 {result[2]}")
        print(f"最低价: {result[3]:.2f}")
        print(f"最高价: {result[4]:.2f}")
        print(f"日均成交量: {result[5]:,.0f} 股")
        print(f"总成交量: {result[6]:,} 股")

        # 显示最近10条数据
        print("\n最近10条交易数据:")
        recent_data = ch_client.query("""
        SELECT trade_date, open, close, volume, change_pct 
        FROM temp_600406_daily_kline 
        ORDER BY trade_date DESC 
        LIMIT 10
        """)

        print(
            f"{'日期':<12} {'开盘':<6} {'收盘':<6} {'成交量(股)':<12} {'涨跌幅(%)':<8}"
        )
        print("-" * 50)
        for row in recent_data.result_rows:
            date_str = row[0].strftime("%Y-%m-%d")
            print(
                f"{date_str:<12} {row[1]:<6.2f} {row[2]:<6.2f} {row[3]:<12,} {row[4]:<8.2f}"
            )

        # 计算涨跌幅统计
        pct_stats = ch_client.query("""
        SELECT 
            COUNT(*) FILTER (WHERE change_pct > 0) as up_days,
            COUNT(*) FILTER (WHERE change_pct < 0) as down_days,
            COUNT(*) FILTER (WHERE change_pct = 0) as flat_days,
            MAX(change_pct) as max_gain,
            MIN(change_pct) as max_loss
        FROM temp_600406_daily_kline
        """)

        pct_result = pct_stats.result_rows[0]
        print(f"\n涨跌统计:")
        print(f"上涨天数: {pct_result[0]} 天")
        print(f"下跌天数: {pct_result[1]} 天")
        print(f"平盘天数: {pct_result[2]} 天")
        print(f"最大单日涨幅: {pct_result[3]:.2f}%")
        print(f"最大单日跌幅: {pct_result[4]:.2f}%")

    except Exception as e:
        print(f"❌ 数据验证失败: {e}")
        traceback.print_exc()
        return

    print(f"\n=== 下载完成 ===")
    print(f"数据已存储到ClickHouse表: default.temp_600406_daily_kline")
    print(f"查询示例: SELECT * FROM temp_600406_daily_kline ORDER BY trade_date")


if __name__ == "__main__":
    main()
