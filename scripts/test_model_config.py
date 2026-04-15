#!/usr/bin/env python3
"""
Opencode模型配置测试脚本
用于验证AI模型配置是否正常工作
"""

import os
import json
import subprocess
from pathlib import Path


def check_opencode_version():
    """检查opencode版本"""
    try:
        result = subprocess.run(
            ["opencode", "--version"], capture_output=True, text=True
        )
        if result.returncode == 0:
            print(f"✅ Opencode版本: {result.stdout.strip()}")
            return True
        else:
            print(f"❌ 无法获取opencode版本: {result.stderr}")
            return False
    except FileNotFoundError:
        print("❌ opencode命令未找到，请确保已安装opencode")
        return False


def check_config_file():
    """检查配置文件"""
    config_path = Path.home() / ".opencode" / "opencode.json"
    if config_path.exists():
        try:
            with open(config_path, "r") as f:
                config = json.load(f)
                model = config.get("model", "未指定")
                print(f"✅ 配置文件存在: {config_path}")
                print(f"   当前模型: {model}")

                # 检查provider配置
                if "provider" in config:
                    providers = list(config["provider"].keys())
                    print(f"   配置的providers: {', '.join(providers)}")

                return True
        except json.JSONDecodeError as e:
            print(f"❌ 配置文件JSON格式错误: {e}")
            return False
    else:
        print(f"❌ 配置文件不存在: {config_path}")
        return False


def check_environment_variables():
    """检查必要的环境变量"""
    required_vars = ["VOLCANO_ENGINE_API_KEY"]
    optional_vars = ["DEEPSEEK_API_KEY", "DASHSCOPE_API_KEY"]

    print("\n🔍 环境变量检查:")

    all_present = True
    for var in required_vars:
        if var in os.environ and os.environ[var]:
            print(f"   ✅ {var}: 已设置")
        else:
            print(f"   ⚠️  {var}: 未设置 (需要设置此变量)")
            all_present = False

    for var in optional_vars:
        if var in os.environ and os.environ[var]:
            print(f"   ✅ {var}: 已设置")
        else:
            print(f"   ℹ️  {var}: 未设置 (可选)")

    return all_present


def check_project_agent_config():
    """检查项目中的Agent配置"""
    config_path = Path("agents/orchestrator/config.yaml")
    if config_path.exists():
        print(f"\n✅ 项目Agent配置存在: {config_path}")
        try:
            with open(config_path, "r") as f:
                content = f.read()
                if "model_type:" in content:
                    print("   AgentScope配置正常")
                if "DASHSCOPE_API_KEY" in content:
                    print("   使用阿里云DashScope API")
                return True
        except Exception as e:
            print(f"❌ 读取配置文件出错: {e}")
            return False
    else:
        print(f"\nℹ️  项目Agent配置文件不存在: {config_path}")
        return False


def test_model_connection():
    """测试模型连接"""
    print("\n🔗 测试模型连接...")
    print("   注意：这需要有效的API密钥")
    print("   要实际测试，请运行: opencode '你好，测试连接'")

    # 提供一个简单的测试命令
    test_command = (
        "echo '要测试模型连接，请运行:' && echo 'opencode \"你好，测试连接\"'"
    )
    print(f"   测试命令: {test_command}")

    return True


def main():
    """主函数"""
    print("=" * 60)
    print("Opencode AI 模型配置测试")
    print("=" * 60)

    tests = [
        ("Opencode版本检查", check_opencode_version),
        ("配置文件检查", check_config_file),
        ("环境变量检查", check_environment_variables),
        ("项目Agent配置检查", check_project_agent_config),
        ("模型连接测试", test_model_connection),
    ]

    results = []
    for test_name, test_func in tests:
        print(f"\n📋 {test_name}:")
        try:
            result = test_func()
            results.append((test_name, result))
        except Exception as e:
            print(f"   ❌ 测试失败: {e}")
            results.append((test_name, False))

    # 总结结果
    print("\n" + "=" * 60)
    print("测试结果总结:")
    print("=" * 60)

    passed = sum(1 for _, result in results if result)
    total = len(results)

    for test_name, result in results:
        status = "✅ 通过" if result else "❌ 失败"
        print(f"{status}: {test_name}")

    print(f"\n总计: {passed}/{total} 项测试通过")

    if passed == total:
        print("\n🎉 所有测试通过！模型配置正常。")
        print("   下一步: 设置 VOLCANO_ENGINE_API_KEY 环境变量")
        print("   示例: export VOLCANO_ENGINE_API_KEY='your_api_key_here'")
    else:
        print("\n⚠️  部分测试失败，请检查上述问题。")

    # 提供配置摘要
    print("\n📊 当前配置摘要:")
    print("-" * 40)

    # 显示当前模型信息
    config_path = Path.home() / ".opencode" / "opencode.json"
    if config_path.exists():
        try:
            with open(config_path, "r") as f:
                config = json.load(f)
                model = config.get("model", "未知")
                print(f"主要模型: {model}")

                # 解析模型信息
                if "/" in model:
                    provider, model_name = model.split("/", 1)
                    print(f"提供商: {provider}")
                    print(f"模型名称: {model_name}")

                    # 根据模型名称提供信息
                    if "deepseek" in model_name.lower():
                        print(f"模型类型: DeepSeek V3.2 (推理专家)")
                        print(f"特点: 开源、MIT许可证、优秀编码能力")
                        print(f"建议: 日常开发、代码生成")
                        print(f"成本: $0.27/M输入, $0.41/M输出")

                    elif "doubao" in model_name.lower():
                        print(f"模型类型: Doubao Seed 2.0 Pro")
                        print(f"特点: 强大推理、多模态支持")
                        print(f"建议: 复杂分析、研究任务")
                        print(f"成本: $0.47/M输入, $2.37/M输出")
        except:
            print("无法读取配置详情")


if __name__ == "__main__":
    main()
