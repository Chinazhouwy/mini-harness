#!/usr/bin/env python3
"""
测试通达信数据格式解析
"""

import sys
from pathlib import Path

# 添加当前目录到路径
sys.path.insert(0, str(Path(__file__).parent))

try:
    from mootdx.quotes import Quotes
    from mootdx.reader import Reader
    from mootdx.consts import MARKET_SH

    print("✅ mootdx 库已成功导入")

    # 测试Quotes客户端（在线数据）
    print("\n--- 测试在线数据 ---")
    client = Quotes.factory(market="std")  # 标准市场

    try:
        # 尝试获取某只股票的信息
        stock_info = client.stocks(market=MARKET_SH)
        print(
            f"📊 可获取的上海市场股票数量: {len(stock_info) if stock_info is not None else 0}"
        )

        # 尝试获取历史数据
        if stock_info is not None and len(stock_info) > 0:
            sample_code = stock_info.iloc[0]["code"]
            print(f"📈 示例股票代码: {sample_code}")

            # 获取日K线数据
            try:
                daily_data = client.bars(symbol=sample_code, frequency=9, offset=10)
                if daily_data is not None and not daily_data.empty:
                    print(
                        f"✅ 成功获取 {sample_code} 日K线数据，共 {len(daily_data)} 条记录"
                    )
                    print(
                        f"   日期范围: {daily_data.index.min()} 到 {daily_data.index.max()}"
                    )
                    print(f"   包含字段: {list(daily_data.columns)}")
                else:
                    print("❌ 无法获取日K线数据")
            except Exception as bar_error:
                print(f"⚠️  bars()方法失败: {bar_error}")
                # 尝试其他方法
                try:
                    history_data = client.history(
                        symbol=sample_code, start="2024-01-01", end="2024-01-10"
                    )
                    if history_data is not None and not history_data.empty:
                        print(
                            f"✅ 使用history()成功获取数据: {len(history_data)} 条记录"
                        )
                        print(f"   数据结构:\n{history_data.head(3)}")
                except Exception as hist_error:
                    print(f"⚠️  history()方法也失败: {hist_error}")
    except Exception as e:
        print(f"⚠️  在线数据获取失败: {e}")

    print("\n--- 测试本地文件读取 ---")
    # 测试Reader客户端（本地通达信数据文件）
    reader = Reader.factory(market="std")

    # 查找可能的数据目录
    tdx_paths = [
        "/Applications/TongHuaShun",  # Mac版通达信
        "/Applications/同花顺",
        "~/Applications/TongHuaShun",
        "~/Library/Application Support/TongHuaShun",
        "C:/new_tdx",  # Windows常见路径
        "D:/new_tdx",
    ]

    print("搜索通达信数据目录...")
    for path in tdx_paths:
        expanded_path = Path(path).expanduser()
        if expanded_path.exists():
            print(f"✅ 发现通达信目录: {expanded_path}")
            # 尝试读取数据
            try:
                # 查找日线数据文件
                day_files = list(expanded_path.rglob("*.day"))
                if day_files:
                    print(f"   发现 {len(day_files)} 个.day文件")
                    # 尝试读取第一个文件
                    sample_file = day_files[0]
                    print(f"   尝试读取: {sample_file.name}")
            except Exception as e:
                print(f"   读取失败: {e}")

    print("\n📋 mootdx 可用功能:")
    print("1. quotes.bars() - 获取K线数据 (1分钟, 5分钟, 日线等)")
    print("2. quotes.minute() - 获取分时数据")
    print("3. quotes.finance() - 获取财务数据")
    print("4. quotes.index_bars() - 获取指数K线")
    print("5. reader.daily() - 读取本地.day文件")
    print("6. reader.minute() - 读取本地.min文件")
    print("7. reader.xdxr() - 读取除权除息文件")

except ImportError as e:
    print(f"❌ mootdx 导入失败: {e}")
    print("请确保已安装: pip install mootdx")
except Exception as e:
    print(f"❌ 测试过程中出错: {e}")
