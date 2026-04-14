#!/bin/bash

# ========================================
# Lombok 配置脚本
# 用于修复 Lombok 在 IDE 中的识别问题
# ========================================

echo "========================================"
echo "Lombok 配置修复脚本"
echo "========================================"
echo ""

# 检查是否在项目根目录
if [[ ! -d "services/strategy-service" ]]; then
    echo "❌ 错误：请在此项目的根目录下运行此脚本"
    exit 1
fi

echo "步骤 1: 检查 Maven 配置..."
cd services/strategy-service

# 1. 验证 Maven 配置
echo "检查 pom.xml Lombok 配置..."
if grep -q "lombok" pom.xml; then
    echo "✅ Lombok 依赖已配置"
else
    echo "❌ 错误：pom.xml 中未找到 Lombok 依赖"
    exit 1
fi

echo ""

# 2. 清理并重新编译项目
echo "步骤 2: 清理并重新编译项目..."
mvn clean compile

if [[ $? -eq 0 ]]; then
    echo "✅ 编译成功"
else
    echo "❌ 编译失败，请检查错误信息"
    exit 1
fi

echo ""

# 3. 检查生成的源代码
echo "步骤 3: 检查生成的代码..."
if [[ -d "target/generated-sources/annotations" ]]; then
    echo "✅ 注解处理工作正常"
else
    echo "⚠️  警告：未找到生成的源代码目录"
    echo "这可能是因为项目未正确配置注解处理"
fi

echo ""

# 4. 验证实体类
echo "步骤 4: 验证 Lombok 注解..."
echo "创建测试类验证 Lombok 功能..."

# 创建测试验证类
cat > src/test/java/com/quant/strategy/lombok/ValidateLombok.java << 'EOF'
package com.quant.strategy.lombok;

import lombok.Data;
import lombok.Builder;

@Data
@Builder
class TestLombok {
    private String field;
    
    public static void main(String[] args) {
        TestLombok test = TestLombok.builder()
            .field("Lombok works!")
            .build();
        
        System.out.println(test.getField());
    }
}

public class ValidateLombok {
    public static void main(String[] args) {
        TestLombok.main(args);
    }
}
EOF

echo "步骤 5: 运行验证测试..."
if mvn test -Dtest=com.quant.strategy.lombok.ValidateLombok 2>/dev/null | grep -q "Lombok works!"; then
    echo "✅ Lombok 功能正常！"
    echo ""
    echo "========================================"
    echo "配置完成！"
    echo "========================================"
    echo ""
    echo "🎉 Lombok 现在应该可以在你的 IDE 中正常工作了。"
    echo ""
    echo "下一步操作："
    echo "1. 如果你使用 Eclipse："
    echo "   - 确保已安装 Lombok 插件"
    echo "   - 右键项目 > Maven > Update Project..."
    echo "   - 选择 'Force Update of Snapshots/Releases'"
    echo ""
    echo "2. 如果你使用 VSCode："
    echo "   - 安装 'Lombok Annotations Support' 扩展"
    echo "   - 安装 'Java Extension Pack' 扩展"
    echo "   - 重新启动 VSCode"
    echo ""
    echo "3. 如果你使用 IntelliJ IDEA："
    echo "   - 安装 'Lombok' 插件"
    echo "   - 启用 Annotation Processing"
    echo "   - 重新构建项目"
    echo ""
    echo "如果问题仍然存在，请检查:"
    echo "- 是否已安装 JDK 21"
    echo "- Maven 是否已正确安装"
    echo "- IDE 是否启用了注解处理"
    echo ""
else
    echo "❌ Lombok 配置仍存在问题"
    echo "请检查:"
    echo "1. pom.xml 中的 Lombok 配置"
    echo "2. IDE 中的注解处理设置"
    exit 1
fi

# 清理临时文件
rm -f src/test/java/com/quant/strategy/lombok/ValidateLombok.java

echo "脚本执行完成！"
echo "请根据你使用的 IDE 执行相应的配置步骤。"