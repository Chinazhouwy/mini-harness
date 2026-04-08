#!/usr/bin/env python3
"""
A股数据采集管道 - 从mootdx获取数据并导入ClickHouse
"""

import sys
import pandas as pd
import numpy as np
from datetime import datetime, timedelta
from pathlib import Path
import time

# 添加项目根目录到路径
sys.path.insert(0, str(Path(__file__).parent.parent))

try:
    from mootdx.quotes import Quotes
    from mootdx.consts import MARKET_SH, MARKET_SZ

    print("✅ mootdx 导入成功")
except ImportError:
    print("❌ mootdx 未安装，请运行: pip install mootdx")
    sys.exit(1)

try:
    import clickhouse_connect

    print("✅ clickhouse-connect 导入成功")
except ImportError:
    print("❌ clickhouse-connect 未安装，请运行: pip install clickhouse-connect")
    sys.exit(1)


class AShareDataPipeline:
    def __init__(self):
        """初始化数据管道"""
        self.mootdx_client = Quotes.factory(market="std")
        self.clickhouse_client = None
        self._connect_clickhouse()

    def _connect_clickhouse(self):
        """连接ClickHouse数据库"""
        try:
            self.clickhouse_client = clickhouse_connect.get_client(
                host="localhost",
                port=8123,
                username="default",
                password="harness123",
                database="default",  # 使用默认数据库
            )
            print("✅ ClickHouse 连接成功")
        except Exception as e:
            print(f"❌ ClickHouse 连接失败: {e}")
            self.clickhouse_client = None

    def fetch_stock_list(self, market="all"):
        """获取股票列表

        Args:
            market: 'sh'=上海, 'sz'=深圳, 'all'=全部
        """
        print(f"获取{market}市场股票列表...")

        stocks_list = []

        if market in ["sh", "all"]:
            try:
                sh_stocks = self.mootdx_client.stocks(market=MARKET_SH)
                if sh_stocks is not None and not sh_stocks.empty:
                    sh_stocks["exchange"] = "SH"
                    stocks_list.append(sh_stocks)
                    print(f"  上海市场: {len(sh_stocks)} 只股票")
            except Exception as e:
                print(f"  上海市场获取失败: {e}")

        if market in ["sz", "all"]:
            try:
                sz_stocks = self.mootdx_client.stocks(market=MARKET_SZ)
                if sz_stocks is not None and not sz_stocks.empty:
                    sz_stocks["exchange"] = "SZ"
                    stocks_list.append(sz_stocks)
                    print(f"  深圳市场: {len(sz_stocks)} 只股票")
            except Exception as e:
                print(f"  深圳市场获取失败: {e}")

        if stocks_list:
            all_stocks = pd.concat(stocks_list, ignore_index=True)

            # 重命名列以匹配ClickHouse表结构
            all_stocks = all_stocks.rename(
                columns={"code": "stock_code", "name": "short_name"}
            )

            # 添加缺失的列
            all_stocks["full_name"] = None
            all_stocks["market_cap"] = None
            all_stocks["industry"] = None
            all_stocks["region"] = None
            all_stocks["listing_date"] = None
            all_stocks["is_active"] = 1
            all_stocks["update_time"] = datetime.now()

            # 只保留需要的列
            required_columns = [
                "stock_code",
                "short_name",
                "full_name",
                "exchange",
                "market_cap",
                "industry",
                "region",
                "listing_date",
                "is_active",
                "update_time",
            ]

            return all_stocks[required_columns]
        else:
            return pd.DataFrame()

    def insert_stock_list_to_ch(self, stocks_df):
        """将股票列表插入ClickHouse"""
        if stocks_df.empty:
            print("没有股票数据可插入")
            return False

        if self.clickhouse_client is None:
            print("ClickHouse未连接")
            return False

        try:
            # 批量插入数据
            self.clickhouse_client.insert_df(
                "stock_info",
                stocks_df,
                settings={"async_insert": 1, "wait_for_async_insert": 0},
            )
            print(f"✅ 成功插入 {len(stocks_df)} 条股票记录")
            return True
        except Exception as e:
            print(f"❌ 插入失败: {e}")
            return False

    def fetch_daily_kline(self, stock_code, start_date, end_date):
        """获取日K线数据

        Args:
            stock_code: 股票代码
            start_date: 开始日期 'YYYY-MM-DD'
            end_date: 结束日期 'YYYY-MM-DD'
        """
        print(f"获取 {stock_code} 日K线数据 ({start_date} 到 {end_date})...")

        try:
            # 计算天数差
            start_dt = datetime.strptime(start_date, "%Y-%m-%d")
            end_dt = datetime.strptime(end_date, "%Y-%m-%d")
            days_diff = (end_dt - start_dt).days

            # 获取数据 (frequency=9 表示日K)
            kline_data = self.mootdx_client.bars(
                symbol=stock_code,
                frequency=9,
                offset=min(days_diff, 1000),  # 最多1000条
            )

            if kline_data is None or kline_data.empty:
                print(f"  未获取到 {stock_code} 的数据")
                return pd.DataFrame()

            # 重命名列
            kline_data = kline_data.rename(
                columns={
                    "open": "open",
                    "high": "high",
                    "low": "low",
                    "close": "close",
                    "vol": "volume",
                }
            )

            # 添加股票代码
            kline_data["stock_code"] = stock_code

            # 计算其他字段
            if "pre_close" not in kline_data.columns:
                # 如果没有前收盘价，使用前一天的收盘价
                kline_data["pre_close"] = kline_data["close"].shift(1)

            kline_data["change"] = kline_data["close"] - kline_data["pre_close"]
            kline_data["change_pct"] = (
                kline_data["change"] / kline_data["pre_close"]
            ) * 100

            # 计算振幅
            kline_data["amplitude"] = (
                (kline_data["high"] - kline_data["low"]) / kline_data["pre_close"]
            ) * 100

            # 添加成交金额（假设成交金额=成交量*收盘价）
            kline_data["amount"] = (
                kline_data["volume"] * kline_data["close"] / 10000
            )  # 转换为万元

            # 添加换手率（需要流通股本，这里暂时设为None）
            kline_data["turnover_rate"] = None

            # 添加更新时间
            kline_data["update_time"] = datetime.now()

            # 确保trade_date是日期类型
            if not isinstance(kline_data.index, pd.DatetimeIndex):
                # 尝试将索引转换为日期
                try:
                    kline_data.index = pd.to_datetime(kline_data.index)
                except:
                    pass

            # 重置索引，使trade_date成为列
            kline_data = kline_data.reset_index()

            # 处理可能的重复索引
            if kline_data.index.duplicated().any():
                kline_data = kline_data[~kline_data.index.duplicated()]

            # 重命名日期列
            date_col = None
            for col in ["datetime", "date", "time", "trade_date"]:
                if col in kline_data.columns:
                    date_col = col
                    break

            if date_col:
                kline_data = kline_data.rename(columns={date_col: "trade_date"})
            else:
                # 如果没有日期列，创建一个
                kline_data["trade_date"] = pd.to_datetime(kline_data.index).date

            # 确保trade_date是日期类型
            kline_data["trade_date"] = pd.to_datetime(kline_data["trade_date"]).dt.date

            print(f"  成功获取 {len(kline_data)} 条日K数据")
            return kline_data

        except Exception as e:
            print(f"  获取失败: {e}")
            return pd.DataFrame()

    def insert_daily_kline_to_ch(self, kline_df):
        """将日K线数据插入ClickHouse"""
        if kline_df.empty:
            print("没有K线数据可插入")
            return False

        if self.clickhouse_client is None:
            print("ClickHouse未连接")
            return False

        # 确保列顺序匹配表结构
        required_columns = [
            "trade_date",
            "stock_code",
            "pre_close",
            "open",
            "high",
            "low",
            "close",
            "volume",
            "amount",
            "turnover_rate",
            "change",
            "change_pct",
            "amplitude",
            "update_time",
        ]

        # 只保留需要的列
        kline_df = kline_df[
            [col for col in required_columns if col in kline_df.columns]
        ]

        try:
            self.clickhouse_client.insert_df(
                "daily_kline",
                kline_df,
                settings={"async_insert": 1, "wait_for_async_insert": 0},
            )
            print(f"✅ 成功插入 {len(kline_df)} 条日K线记录")
            return True
        except Exception as e:
            print(f"❌ 插入失败: {e}")
            return False

    def test_pipeline(self):
        """测试数据管道"""
        print("\n=== 测试数据管道 ===\n")

        # 1. 测试获取股票列表
        print("1. 测试获取股票列表...")
        stocks = self.fetch_stock_list(market="all")
        if not stocks.empty:
            print(f"   获取到 {len(stocks)} 只股票")
            print(
                f"   样本股票:\n{stocks[['stock_code', 'short_name', 'exchange']].head()}"
            )

            # 测试插入ClickHouse
            if self.clickhouse_client:
                success = self.insert_stock_list_to_ch(
                    stocks.head(10)
                )  # 只插入前10条测试
                if success:
                    print("   ✅ 股票列表插入测试成功")
                else:
                    print("   ❌ 股票列表插入测试失败")
        else:
            print("   ❌ 未获取到股票列表")

        # 2. 测试获取日K线数据
        print("\n2. 测试获取日K线数据...")
        test_stocks = ["000001", "000002", "600036"]  # 测试股票
        for stock in test_stocks[:1]:  # 只测试第一只
            kline_data = self.fetch_daily_kline(
                stock_code=stock, start_date="2024-01-01", end_date="2024-01-10"
            )

            if not kline_data.empty:
                print(
                    f"   {stock} 数据样本:\n{kline_data[['trade_date', 'open', 'close', 'volume']].head()}"
                )

                # 测试插入ClickHouse
                if self.clickhouse_client:
                    success = self.insert_daily_kline_to_ch(kline_data)
                    if success:
                        print(f"   ✅ {stock} 日K线插入测试成功")
                    else:
                        print(f"   ❌ {stock} 日K线插入测试失败")
            else:
                print(f"   ❌ 未获取到 {stock} 的K线数据")

        print("\n=== 测试完成 ===")


def main():
    """主函数"""
    print("=== A股数据采集管道 ===\n")

    # 创建管道实例
    pipeline = AShareDataPipeline()

    if pipeline.clickhouse_client is None:
        print("无法连接到ClickHouse，请检查配置")
        return

    # 运行测试
    pipeline.test_pipeline()

    print("\n=== 使用说明 ===")
    print("1. 获取股票列表: pipeline.fetch_stock_list(market='all')")
    print(
        "2. 获取日K线: pipeline.fetch_daily_kline('000001', '2024-01-01', '2024-01-10')"
    )
    print("3. 插入数据: pipeline.insert_stock_list_to_ch(stocks_df)")
    print("4. 运行测试: pipeline.test_pipeline()")


if __name__ == "__main__":
    main()
