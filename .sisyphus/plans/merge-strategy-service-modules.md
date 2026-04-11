# strategy-service 模块合并重构计划

## 问题分析

当前 `strategy-service` 已经是 `services/` 目录下的一个服务模块，又细分成 4 个子模块导致层次过深：

```
services/
└── strategy-service/           # 已经是服务级别
    ├── strategy-api/           # 子模块 - 过度细分
    ├── strategy-core/          # 子模块 - 过度细分
    ├── strategy-persistence/   # 子模块 - 过度细分
    └── strategy-application/   # 子模块 - 过度细分
```

这违反了 **KISS 原则** 和微服务设计的合理粒度。

## 目标架构

合并为**单模块**，但通过**包结构**保持分层清晰：

```
services/
└── strategy-service/           # 单模块 Spring Boot 应用
    ├── pom.xml
    └── src/main/java/com/quant/strategy/
        ├── api/                # 对外暴露的 DTO 和接口
        │   ├── dto/
        │   │   ├── StrategyConfigDto.java
        │   │   ├── SignalDto.java
        │   │   ├── BacktestRequestDto.java
        │   │   └── BacktestResultDto.java
        │   └── feign/
        │       └── StrategyServiceClient.java
        ├── core/               # 核心业务逻辑
        │   ├── factory/
        │   │   └── StrategyFactory.java
        │   ├── generator/
        │   │   └── SignalGenerator.java
        │   ├── executor/
        │   │   └── BacktestExecutor.java
        │   └── indicator/      # 预留自定义指标
        ├── domain/             # 领域实体
        │   ├── entity/
        │   │   └── StrategyConfig.java
        │   ├── repository/
        │   │   └── StrategyConfigRepository.java
        │   └── enums/
        │       └── Signal.java
        ├── infrastructure/     # 基础设施
        │   ├── config/
        │   │   ├── Ta4jConfig.java
        │   │   ├── ThreadPoolConfig.java
        │   │   └── ClickHouseConfig.java
        │   └── client/
        │       └── QuoteClient.java
        └── web/                # 对外接口层
            ├── controller/
            │   └── StrategyController.java
            └── service/
                ├── StrategyApplicationService.java
                └── BacktestApplicationService.java
```

## 执行步骤

### Wave 1: 准备阶段
- [ ] 1. 备份当前 strategy-service 目录
- [ ] 2. 创建新的单模块目录结构

### Wave 2: 合并 API 层
- [ ] 3. 将 strategy-api 中的 DTO 移动到 `api/dto/` 包
- [ ] 4. 将 FeignClient 移动到 `api/feign/` 包

### Wave 3: 合并 Core 层
- [ ] 5. 将 strategy-core 中的类移动到 `core/` 包下对应子包
- [ ] 6. 修复所有 import 路径

### Wave 4: 合并 Persistence 层
- [ ] 7. 将 strategy-persistence 中的 Entity/Repository 移动到 `domain/` 包
- [ ] 8. 更新 JPA 配置

### Wave 5: 合并 Application 层
- [ ] 9. 将 strategy-application 中的类移动到 `web/` 和 `infrastructure/` 包
- [ ] 10. 将 application.yml 移到正确位置

### Wave 6: 配置整合
- [ ] 11. 更新 pom.xml 为单模块配置（添加所有依赖）
- [ ] 12. 删除子模块目录
- [ ] 13. 验证 Maven 构建

### Wave 7: 验证
- [ ] 14. 运行 Maven compile 验证无错误
- [ ] 15. 检查 LSP 诊断干净
- [ ] 16. 确认启动类位置正确

## 关键变更

### pom.xml 变化
- 移除 `<packaging>pom</packaging>` 和 `<modules>`
- 添加所有依赖（ta4j, clickhouse-jdbc, spring-boot-starter-data-jpa 等）
- 保留 spring-boot-maven-plugin

### 类引用变化
```java
// 旧路径（子模块）
import com.quant.strategy.api.model.StrategyConfig;

// 新路径（单模块包结构）
import com.quant.strategy.api.dto.StrategyConfigDto;
// 或
import com.quant.strategy.domain.entity.StrategyConfig;
```

## 完成标准
- [ ] 单模块 Maven 项目结构
- [ ] `mvn clean compile` 成功
- [ ] 无 LSP 错误
- [ ] 启动类可以正常运行
- [ ] 包结构清晰分层

## 注意事项
1. DTO 和 Entity 可能同名，需要加后缀区分（如 StrategyConfigDto vs StrategyConfigEntity）
2. 确保所有 Spring 注解路径正确
3. 保留原有的 ClickHouse 和 Ta4j 配置

## 下一步
运行 `/start-work` 让 Sisyphus 执行这个重构计划。
