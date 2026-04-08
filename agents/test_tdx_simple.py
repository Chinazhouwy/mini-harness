import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

try:
    from mootdx.quotes import Quotes
    from mootdx.consts import MARKET_SH

    print("测试mootdx核心功能")

    client = Quotes.factory(market="std")

    # 1. 测试获取股票列表
    print("\n1. 获取股票列表...")
    stocks = client.stocks(market=MARKET_SH)
    print(f"股票数量: {len(stocks)}")
    print(f"列名: {list(stocks.columns)}")
    print(f"前5只:\n{stocks.head()}")

    # 取一个有效的股票代码（不是999999）
    valid_stocks = stocks[stocks["code"].str.match(r"^\d{6}$")]
    if not valid_stocks.empty:
        sample_code = valid_stocks.iloc[0]["code"]
        sample_name = valid_stocks.iloc[0]["name"]
        print(f"\n   使用股票: {sample_code} ({sample_name})")

        # 2. 测试获取分时数据
        print("\n2. 获取分时数据...")
        try:
            minute_data = client.minute(symbol=sample_code)
            if minute_data is not None and not minute_data.empty:
                print(f"   成功获取 {len(minute_data)} 条分时数据")
                print(f"   列名: {list(minute_data.columns)}")
                print(f"   时间范围: {minute_data.index[0]} 到 {minute_data.index[-1]}")
                print(
                    f"   价格范围: {minute_data['price'].min():.2f} - {minute_data['price'].max():.2f}"
                )
            else:
                print("   无法获取分时数据")
        except Exception as e:
            print(f"   分时数据获取失败: {type(e).__name__}: {e}")

        # 3. 测试获取5分钟K线
        print("\n3. 获取5分钟K线...")
        try:
            kline_5min = client.bars(symbol=sample_code, frequency=0, offset=50)
            if kline_5min is not None and not kline_5min.empty:
                print(f"   成功获取 {len(kline_5min)} 条5分钟K线")
                print(f"   列名: {list(kline_5min.columns)}")
                print(f"   前3条数据:\n{kline_5min.head(3)}")
            else:
                print("   无法获取5分钟K线")
        except Exception as e:
            print(f"   5分钟K线获取失败: {type(e).__name__}: {e}")

        # 4. 测试获取财务数据
        print("\n4. 获取财务数据...")
        try:
            finance_data = client.finance(symbol=sample_code)
            if finance_data is not None and not finance_data.empty:
                print(f"   成功获取财务数据，共 {len(finance_data)} 条记录")
                print(f"   列名: {list(finance_data.columns)}")
                print(f"   最新财务数据:\n{finance_data.head(1)}")
            else:
                print("   无法获取财务数据")
        except Exception as e:
            print(f"   财务数据获取失败: {type(e).__name__}: {e}")

    # 5. 测试获取指数数据
    print("\n5. 获取指数数据...")
    try:
        index_data = client.index_bars(symbol="000001", frequency=9, offset=10)
        if index_data is not None and not index_data.empty:
            print(f"   成功获取上证指数日K线: {len(index_data)} 条记录")
        else:
            print("   无法获取指数数据")
    except Exception as e:
        print(f"   指数数据获取失败: {type(e).__name__}: {e}")

    print("\n✅ mootdx 测试完成")

except ImportError as e:
    print(f"导入失败: {e}")
except Exception as e:
    print(f"测试失败: {type(e).__name__}: {e}")
    import traceback

    traceback.print_exc()
