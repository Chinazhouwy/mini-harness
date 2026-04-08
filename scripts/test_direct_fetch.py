#!/usr/bin/env python3
"""
直接测试mootdx数据获取
"""

from mootdx.quotes import Quotes
import pandas as pd

client = Quotes.factory(market="std")

print("1. 测试获取股票000001的日K线...")
try:
    # 尝试不同的频率
    for freq in [9, 5, 2, 1]:  # 9=日K, 5=周K, 2=月K, 1=1分钟
        print(f"\n   频率 {freq}:")
        data = client.bars(symbol="000001", frequency=freq, offset=10)
        if data is not None:
            print(f"     形状: {data.shape}")
            print(f"     列名: {list(data.columns)}")
            print(f"     索引类型: {type(data.index)}")
            print(f"     前3行索引: {data.index[:3] if len(data) > 0 else '空'}")

            # 显示具体数据
            if not data.empty:
                print(f"     数据样本:\n{data.head(2)}")
        else:
            print("     返回None")
except Exception as e:
    print(f"   错误: {e}")

print("\n2. 测试获取分时数据...")
try:
    minute_data = client.minute(symbol="000001")
    if minute_data is not None:
        print(f"   形状: {minute_data.shape}")
        print(f"   列名: {list(minute_data.columns)}")
        print(f"   数据样本:\n{minute_data.head(3)}")
    else:
        print("   返回None")
except Exception as e:
    print(f"   错误: {e}")

print("\n3. 测试获取指数数据...")
try:
    index_data = client.index_bars(symbol="000001", frequency=9, offset=10)
    if index_data is not None:
        print(f"   形状: {index_data.shape}")
        print(f"   列名: {list(index_data.columns)}")
        print(f"   数据样本:\n{index_data.head(3)}")
    else:
        print("   返回None")
except Exception as e:
    print(f"   错误: {e}")

print("\n4. 测试获取财务数据...")
try:
    finance_data = client.finance(symbol="000001")
    if finance_data is not None:
        print(f"   形状: {finance_data.shape}")
        print(f"   列数: {len(finance_data.columns)}")
        print(f"   前5列: {list(finance_data.columns)[:5]}")
        print(f"   数据样本:\n{finance_data.head(1)}")
    else:
        print("   返回None")
except Exception as e:
    print(f"   错误: {e}")
