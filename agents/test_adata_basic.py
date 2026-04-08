import adata
import time

print("AData基础连接测试")
start = time.time()

try:
    # 只测试最简单的功能
    print("尝试获取股票代码...")
    codes = adata.stock.info.all_code()

    if codes is not None:
        elapsed = time.time() - start
        print(f"✅ 成功! 用时: {elapsed:.2f}秒")
        print(f"股票数量: {len(codes)}")
        print(f"数据类型: {type(codes)}")
        print(f"列名: {list(codes.columns)[:5]}...")  # 只显示前5列
        print(f"形状: {codes.shape}")

        # 显示几个样本
        print("\n样本数据:")
        print(codes.iloc[:3])
    else:
        print("❌ 返回了None")

except Exception as e:
    elapsed = time.time() - start
    print(f"❌ 失败! 用时: {elapsed:.2f}秒")
    print(f"错误类型: {type(e).__name__}")
    print(f"错误信息: {e}")

    # 尝试设置代理
    print("\n尝试设置代理...")
    try:
        adata.proxy(is_proxy=True, ip="127.0.0.1:7890")  # 常见代理端口
        print("已设置代理，重试...")
        codes = adata.stock.info.all_code()
        if codes is not None:
            print(f"✅ 代理成功! 获取到 {len(codes)} 只股票")
        else:
            print("❌ 即使使用代理也失败")
    except Exception as proxy_error:
        print(f"❌ 代理设置失败: {proxy_error}")
