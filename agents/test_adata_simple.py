import adata
import pandas as pd

print("AData快速测试")

# 1. 获取股票代码
print("\n1. 获取股票代码...")
codes = adata.stock.info.all_code()
print(f"股票数量: {len(codes)}")
print(f"列名: {list(codes.columns)}")
print(f"前5只:\n{codes.head()}")

# 2. 获取简单行情
print("\n2. 获取平安银行(000001)最近5天行情...")
try:
    market = adata.stock.market.get_market(
        stock_code="000001", k_type=1, start_date="2024-06-01", end_date="2024-06-05"
    )
    if market is not None:
        print(f"获取到 {len(market)} 条数据")
        print(market[["open", "close", "high", "low", "volume"]].head())
    else:
        print("未获取到数据")
except Exception as e:
    print(f"错误: {e}")

# 3. 测试实时行情
print("\n3. 测试实时行情...")
try:
    current = adata.stock.market.list_market_current(["000001", "600036"])
    if current is not None:
        print(f"实时行情:\n{current[['stock_code', 'price', 'change', 'change_pct']]}")
except Exception as e:
    print(f"实时行情错误: {e}")
