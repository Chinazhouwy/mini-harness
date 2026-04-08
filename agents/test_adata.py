#!/usr/bin/env python3
"""
测试AData数据源功能
"""

import sys
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

print("=== 测试 AData 数据源 ===")

try:
    import adata

    print(f"✅ AData版本: {adata.__version__}")

    print("\n1. 测试获取所有股票代码...")
    try:
        all_codes = adata.stock.info.all_code()
        print(f"   成功获取 {len(all_codes) if all_codes is not None else 0} 只股票")
        if all_codes is not None and not all_codes.empty:
            print(f"   列名: {list(all_codes.columns)}")
            print(f"   示例股票:\n{all_codes.head()}")
    except Exception as e:
        print(f"   ❌ 获取股票代码失败: {type(e).__name__}: {e}")

    print("\n2. 测试获取单只股票历史行情...")
    try:
        # 测试获取平安银行(000001)的历史行情
        market_data = adata.stock.market.get_market(
            stock_code="000001",
            k_type=1,  # 日K
            start_date="2024-01-01",
            end_date="2024-01-10",
        )
        if market_data is not None and not market_data.empty:
            print(f"   成功获取 {len(market_data)} 条日K数据")
            print(f"   列名: {list(market_data.columns)}")
            print(
                f"   时间范围: {market_data.index.min()} 到 {market_data.index.max()}"
            )
            print(f"   前3条数据:\n{market_data.head(3)}")

            # 检查数据结构
            print(f"\n   数据统计:")
            print(
                f"   开盘价范围: {market_data['open'].min():.2f} - {market_data['open'].max():.2f}"
            )
            print(
                f"   收盘价范围: {market_data['close'].min():.2f} - {market_data['close'].max():.2f}"
            )
            print(
                f"   成交量范围: {market_data['volume'].min():.0f} - {market_data['volume'].max():.0f}"
            )
        else:
            print("   ⚠️  未获取到数据")
    except Exception as e:
        print(f"   ❌ 获取行情数据失败: {type(e).__name__}: {e}")
        traceback.print_exc()

    print("\n3. 测试获取概念板块数据...")
    try:
        # 获取概念板块列表
        concept_codes = adata.stock.info.all_concept_code_ths()
        if concept_codes is not None and not concept_codes.empty:
            print(f"   成功获取 {len(concept_codes)} 个概念板块")
            print(f"   前5个概念:\n{concept_codes.head()}")

            # 测试获取一个概念的行情
            if not concept_codes.empty:
                sample_concept = concept_codes.iloc[0]["index_code"]
                concept_name = concept_codes.iloc[0]["index_name"]
                print(f"\n   测试概念: {concept_name} ({sample_concept})")

                concept_data = adata.stock.market.get_market_concept_ths(
                    index_code=sample_concept,
                    k_type=1,
                    start_date="2024-01-01",
                    end_date="2024-01-10",
                )
                if concept_data is not None and not concept_data.empty:
                    print(f"   成功获取概念行情: {len(concept_data)} 条数据")
                else:
                    print("   ⚠️  未获取到概念行情数据")
    except Exception as e:
        print(f"   ❌ 获取概念数据失败: {type(e).__name__}: {e}")

    print("\n4. 测试获取实时行情...")
    try:
        # 获取多只股票的最新行情
        current_market = adata.stock.market.list_market_current(
            stock_code=["000001", "000002", "000858"]
        )
        if current_market is not None and not current_market.empty:
            print(f"   成功获取 {len(current_market)} 只股票的实时行情")
            print(f"   列名: {list(current_market.columns)}")
            print(f"   实时数据:\n{current_market}")
        else:
            print("   ⚠️  未获取到实时行情")
    except Exception as e:
        print(f"   ❌ 获取实时行情失败: {type(e).__name__}: {e}")

    print("\n5. 测试财务数据...")
    try:
        finance_data = adata.stock.finance.get_core_index(stock_code="000001")
        if finance_data is not None and not finance_data.empty:
            print(f"   成功获取财务数据: {len(finance_data)} 条记录")
            print(f"   列名: {list(finance_data.columns)}")
            print(f"   最新财务指标:\n{finance_data.head(1)}")
        else:
            print("   ⚠️  未获取到财务数据")
    except Exception as e:
        print(f"   ❌ 获取财务数据失败: {type(e).__name__}: {e}")

    print("\n=== AData 数据源测试总结 ===")
    print("AData提供了以下数据类别:")
    print("1. 股票基本信息 (代码、名称、交易所)")
    print("2. 历史行情数据 (日K、周K、月K)")
    print("3. 概念板块数据 (同花顺、东方财富)")
    print("4. 实时行情数据")
    print("5. 财务核心指标")
    print("6. 基金(ETF)、债券(可转债)数据")
    print("7. 舆情数据 (北向资金、融资融券、热门榜单)")

except ImportError as e:
    print(f"❌ AData导入失败: {e}")
    print("安装命令: pip install adata")
    print("或使用镜像源: pip install adata -i http://mirrors.aliyun.com/pypi/simple/")
except Exception as e:
    print(f"❌ 测试过程中出错: {type(e).__name__}: {e}")
    traceback.print_exc()
