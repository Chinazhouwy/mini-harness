# Lombok 配置指南

本文档提供 Lombok 在各个 IDE 中的详细配置指南，解决实体类中 `getStockCode()`, `getTradeDate()` 等 getter 方法无法识别的问题。

## 当前项目状态

- **项目类型**: Eclipse + Maven
- **Lombok 依赖**: 已添加 (`org.projectlombok:lombok`)
- **JDK 版本**: Java 21
- **主要问题**: LSP 无法识别 Lombok 生成的 getter/setter 方法

## 1. Eclipse 配置

### 1.1 安装 Lombok Eclipse 插件

**方法一：自动安装（推荐）**

1. 下载 Lombok 最新版 JAR：
   ```bash
   wget https://projectlombok.org/downloads/lombok.jar
   ```

2. 运行安装程序：
   ```bash
   java -jar lombok.jar
   ```

3. 选择 Eclipse 安装目录
4. 点击 "Install / Update"
5. 重启 Eclipse

**方法二：手动安装**

1. 将 `lombok.jar` 复制到 Eclipse 安装目录
2. 编辑 `eclipse.ini`，在末尾添加：
   ```
   -javaagent:lombok.jar
   -Xbootclasspath/a:lombok.jar
   ```

### 1.2 Eclipse 项目配置

**确保 Annotation Processing 开启：**

1. **项目 > 属性 > Java Compiler > Annotation Processing**
   - ☑ Enable annotation processing
   - Generated source directory: `target/generated-sources/annotations`

2. **项目 > 属性 > Java Compiler > Annotation Processing > Factory Path**
   - ☑ Enable project specific settings
   - 添加 Maven 依赖中的 `lombok.jar`

3. **刷新项目：**
   ```
   项目 > Clean...
   项目 > Build Automatically (启用)
   ```

### 1.3 验证 Lombok 工作

创建测试类验证：

```java
package com.quant.strategy.test;

import lombok.Data;

@Data
public class TestLombok {
    private String testField;
    
    public static void main(String[] args) {
        TestLombok test = new TestLombok();
        test.setTestField("Hello Lombok");
        System.out.println(test.getTestField()); // 应该正常工作
    }
}
```

## 2. VSCode 配置

### 2.1 必需扩展

安装以下 VSCode 扩展：

1. **Lombok Annotations Support (推荐)**
   - 扩展ID: `GabrielBB.vscode-lombok`
   - 功能：提供 Lombok 注解支持

2. **Java Extension Pack (必需)**
   - 扩展ID: `vscjava.vscode-java-pack`
   - 包含：Language Support for Java, Debugger, Maven等

3. **Spring Boot Extension Pack (可选)**
   - 扩展ID: `vscjava.vscode-spring-boot-pack`

### 2.2 settings.json 配置

在项目 `.vscode/settings.json` 中添加：

```json
{
    "java.configuration.updateBuildConfiguration": "automatic",
    "java.compile.nullAnalysis.mode": "automatic",
    "java.referencesCodeLens.enabled": true,
    "java.implementationsCodeLens.enabled": true,
    "java.format.settings.url": ".vscode/eclipse-java-google-style.xml",
    "java.format.settings.profile": "GoogleStyle",
    "java.autobuild.enabled": true,
    "java.jdt.ls.lombokSupport.enabled": true,
    "java.completion.enabled": true,
    "java.compile.annotationProcessing.enabled": true,
    "java.compile.annotationProcessorPath": "${workspaceFolder}/.m2/repository/org/projectlombok/lombok/1.18.30/lombok-1.18.30.jar"
}
```

### 2.3 创建 .vscode 目录结构

```bash
mkdir -p .vscode
touch .vscode/settings.json
touch .vscode/extensions.json
```

`.vscode/extensions.json` 内容：
```json
{
    "recommendations": [
        "vscjava.vscode-java-pack",
        "GabrielBB.vscode-lombok",
        "redhat.java",
        "vscjava.vscode-maven",
        "vscjava.vscode-java-debug"
    ]
}
```

## 3. IntelliJ IDEA 配置

### 3.1 启用 Lombok 插件

1. **安装插件：**
   - `File` > `Settings` > `Plugins`
   - 搜索 "Lombok"
   - 安装 "Lombok Plugin"

2. **启用 Annotation Processing：**
   - `File` > `Settings` > `Build, Execution, Deployment` > `Compiler` > `Annotation Processors`
   - ☑ `Enable annotation processing`
   - Generated sources directory: `target/generated-sources/annotations`

### 3.2 配置 Lombok

**方法一：通过插件配置（推荐）**

1. 打开设置：`File` > `Settings` > `Build, Execution, Deployment` > `Compiler`
2. 在 "Annotation Processors" 中：
   - 勾选 "Enable annotation processing"
   - 勾选 "Obtain processors from project classpath"
   - 设置生成路径为 `target/generated-sources/annotations`

**方法二：通过项目结构配置**

1. `File` > `Project Structure` > `Modules`
2. 选择项目模块
3. 选择 `Dependencies` 标签
4. 确保 Lombok 版本正确

## 4. Maven 配置

### 4.1 检查 Maven 版本

确保 Maven 3.6+：
```bash
mvn --version
```

### 4.2 完整的 pom.xml Lombok 配置

在 `pom.xml` 中添加：

```xml
<properties>
    <!-- Lombok 版本 -->
    <lombok.version>1.18.30</lombok.version>
</properties>

<dependencies>
    <!-- Lombok 依赖 -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>${lombok.version}</version>
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- Maven Compiler Plugin -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.11.0</version>
            <configuration>
                <source>21</source>
                <target>21</target>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                    </path>
                </annotationProcessorPaths>
                <compilerArgs>
                    <arg>-parameters</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 4.3 创建 Lombok 配置文件

在项目根目录创建 `.mvn/lombok.config`：

```properties
lombok.anyConstructor.addConstructorProperties=true
lombok.experimental.flagUsage=WARNING
lombok.copyableAnnotations+=jakarta.persistence.*
lombok.log.fieldName=log
```

## 5. 故障排除

### 5.1 常见问题及解决方案

#### **问题1: "The method getStockCode() is undefined"**
- **原因**: Lombok 未正确生成 getter
- **解决方案**:
  1. 清理并重新构建项目
  2. 确认 Lombok 插件已安装并启用
  3. 重新导入 Maven 项目

#### **问题2: 实体类注解不被识别**
- **原因**: JPA (Jakarta Persistence) 和 Lombok 版本不兼容
- **解决方案**:
  1. 检查 `jakarta.persistence-api` 版本
  2. 确认实体类使用了正确注解
  3. 重新编译项目

#### **问题3: 编译时找不到符号**
- **原因**: Lombok 生成的代码未包含在编译路径中
- **解决方案**:
  1. 确认注解处理已启用
  2. 检查生成的源代码目录
  3. 重新编译

### 5.2 创建测试类验证

在 `src/test/java/com/quant/strategy/lombok/` 创建：

```java
package com.quant.strategy.lombok;

import lombok.Data;

@Data
public class LombokTest {
    private String field1;
    private Integer field2;
    
    public static void main(String[] args) {
        LombokTest test = new LombokTest();
        test.setField1("Test");
        test.setField2(123);
        
        System.out.println("Field1: " + test.getField1());
        System.out.println("Field2: " + test.getField2());
        
        // 测试 toString()
        System.out.println("toString: " + test.toString());
    }
}
```

### 5.3 执行验证步骤

```bash
# 1. 清理项目
mvn clean

# 2. 编译项目
mvn compile

# 3. 测试 Lombok
mvn test -Dtest=com.quant.strategy.lombok.LombokTest

# 4. 检查生成的代码
ls -la target/generated-sources/annotations/
```

## 6. 项目优化建议

### 6.1 创建 .editorconfig

在项目根目录创建 `.editorconfig`：

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

[*.java]
indent_size = 4
continuation_indent_size = 8
tab_width = 4
spaces_around_operators = true

[*.xml]
indent_size = 2

[*.yml]
indent_size = 2

[*.md]
trim_trailing_whitespace = false
```

### 6.2 创建代码质量检查

在 `pom.xml` 中添加代码检查插件：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-checkstyle-plugin</artifactId>
    <version>3.3.0</version>
    <configuration>
        <configLocation>google_checks.xml</configLocation>
        <encoding>UTF-8</encoding>
        <consoleOutput>true</consoleOutput>
        <failsOnError>true</failsOnError>
        <linkXRef>false</linkXRef>
    </configuration>
</plugin>
```

## 7. 快速开始脚本

在项目根目录创建 `setup-lombok.sh`：

```bash
#!/bin/bash

# Lombok 配置脚本
echo "开始配置 Lombok..."

# 1. 清理项目
echo "步骤 1: 清理项目..."
mvn clean

# 2. 验证 Lombok 依赖
echo "步骤 2: 检查 Lombok 依赖..."
mvn dependency:tree | grep lombok

# 3. 编译项目
echo "步骤 3: 编译项目..."
mvn compile

# 4. 测试 Lombok
echo "步骤 4: 测试 Lombok 功能..."
mvn test -Dtest=com.quant.strategy.lombok.LombokTest

# 5. 生成报告
echo "步骤 5: 生成项目报告..."
mvn site

echo "配置完成！请根据您的 IDE 安装 Lombok 插件。"

# 根据操作系统提供建议
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "检测到 macOS，建议使用 VSCode 或 IntelliJ IDEA"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    echo "检测到 Linux，建议使用 Eclipse 或 VSCode"
elif [[ "$OSTYPE" == "cygwin" || "$OSTYPE" == "msys" ]]; then
    echo "检测到 Windows，建议使用 IntelliJ IDEA 或 Eclipse"
fi
```

## 8. 联系和支持

如果以上步骤无法解决问题：

1. **项目团队**: 联系项目开发团队
2. **Lombok 社区**: [Project Lombok Forums](https://projectlombok.org/)
3. **IDE 支持**: 查看对应 IDE 的官方文档

---

**更新记录**:
- 2026-04-14: 创建 Lombok 配置指南
- 2026-04-14: 添加故障排除章节
- 2026-04-14: 完善 Maven 配置

**维护者**: QuantHarness 团队
**最后更新**: 2026-04-14