# Implementation Playbook（单人 Java + AI 实战版）

版本：2026-04-13  
适用范围：一期 MVP（8 周）  
目标：在“不过度简化”的前提下，系统性锻炼 Java 技术栈与 AI 工程能力。

## 1. 总体策略

采用“三线并行、主线优先”：

1. Java 主线：架构分层、策略计算、风控、下单、测试与性能。
2. AI 主线：最小 Agent 协同，逐步引入 LLM 能力。
3. 工程主线：可观测、质检、反馈闭环、文档复盘。

硬约束：

- 每周必须有可运行产物（接口、脚本、页面或报告）。
- 每个阶段结束都要有“可验收结果”。
- 新需求默认归入二期，避免拖垮主线。

## 2. 目录与模块骨架（建议）

```text
services/strategy-service/src/main/java/com/quant/strategy/
  api/
    dto/
      BacktestRequest.java
      BacktestResponse.java
      SignalResponse.java
  core/
    strategy/
      MovingAverageStrategyEngine.java
    risk/
      RiskRuleEngine.java
  domain/
    model/
      KLine.java
      TradeSignal.java
      SimulatedOrder.java
  infrastructure/
    repository/
      KLineRepository.java
      OrderRepository.java
  web/
    controller/
      StrategyController.java
    service/
      StrategyApplicationService.java
```

```text
agents/
  orchestrator/main.py
  technical/main.py
```

## 3. 阶段计划（含交付与验收）

## Phase 1（Week 1-2）：数据与回测

交付：

- 完成 ClickHouse 历史行情读取。
- 完成双均线回测器（Python 或 Java，建议先 Python 快速验证）。
- 输出统一指标：年化收益、最大回撤、夏普。

验收：

- 同一参数重复跑 3 次，结果一致。
- 有固定样例数据可离线运行。

示例：回测输出结构

```json
{
  "symbol": "000001",
  "strategy": "MA(5,20)",
  "annual_return": 0.182,
  "max_drawdown": 0.097,
  "sharpe": 1.26,
  "trades": 42
}
```

## Phase 2（Week 3-4）：Java 服务化

交付：

- `strategy-service` 提供 3 个核心 API：
  - `POST /api/strategy/backtest`
  - `GET /api/strategy/backtest/{id}`
  - `GET /api/strategy/signal?symbol=...`
- 接入 ClickHouse 读数据，PostgreSQL 存结果。

验收：

- Postman/curl 可调用。
- 接口失败时返回统一错误结构。

示例：Controller 骨架

```java
@RestController
@RequestMapping("/api/strategy")
public class StrategyController {
    private final StrategyApplicationService appService;

    public StrategyController(StrategyApplicationService appService) {
        this.appService = appService;
    }

    @PostMapping("/backtest")
    public BacktestResponse runBacktest(@Valid @RequestBody BacktestRequest req) {
        return appService.runBacktest(req);
    }

    @GetMapping("/signal")
    public SignalResponse latestSignal(@RequestParam String symbol) {
        return appService.latestSignal(symbol);
    }
}
```

示例：应用服务骨架

```java
@Service
public class StrategyApplicationService {
    private final MovingAverageStrategyEngine strategyEngine;
    private final RiskRuleEngine riskRuleEngine;

    public BacktestResponse runBacktest(BacktestRequest req) {
        var result = strategyEngine.backtest(req.symbol(), req.fastWindow(), req.slowWindow());
        return BacktestResponse.from(result);
    }
}
```

## Phase 3（Week 5-6）：风控 + 模拟下单 + Agent 最小协同

交付：

- 风控规则落地：
  - 最大仓位限制
  - 止损阈值
  - 单日亏损阈值
- 模拟下单落库。
- `orchestrator` 调用 `technical` 后触发 Java API。

验收：

- 非法订单被风控拒绝。
- 合法订单可落库并回查。

示例：风控引擎骨架

```java
@Component
public class RiskRuleEngine {
    public RiskDecision evaluate(OrderContext ctx) {
        if (ctx.positionRatio() > 0.30) return RiskDecision.reject("POSITION_LIMIT");
        if (ctx.estimatedLossRatio() > 0.02) return RiskDecision.reject("STOP_LOSS");
        if (ctx.dailyLossRatio() > 0.05) return RiskDecision.reject("DAILY_LOSS_LIMIT");
        return RiskDecision.pass();
    }
}
```

示例：Agent 调用 Java API

```python
import requests

def fetch_signal(symbol: str) -> dict:
    resp = requests.get("http://localhost:8082/api/strategy/signal", params={"symbol": symbol}, timeout=5)
    resp.raise_for_status()
    return resp.json()
```

## Phase 4（Week 7）：Harness 质检

交付：

- 实现 `harness/validators/quality_check.py`：
  - 运行测试
  - 校验回测阈值
  - 生成报告

验收：

- 一条命令输出通过/失败 + 原因。

示例：质检报告结构

```json
{
  "status": "failed",
  "checks": [
    {"name": "pytest", "ok": true},
    {"name": "backtest_sharpe", "ok": false, "actual": 0.84, "threshold": 1.0}
  ],
  "suggestions": [
    "降低交易频率",
    "提高慢线窗口，减少噪声交易"
  ]
}
```

## Phase 5（Week 8）：前端展示 + 稳态

交付：

- 前端展示信号、回测、订单。
- 关键路径测试补齐。
- 一键演示流程固定。

验收：

- 新机器 30 分钟内跑通。
- 可按文档完成一次端到端演示。

## 4. 每周节奏模板

每周固定四类任务：

1. Build：实现本周核心功能。
2. Verify：补测试与回归。
3. Observe：记录指标（延迟、成功率、错误率）。
4. Document：更新 README/TODOLIST/本手册进度。

建议每天结束前记录三行：

- 今日完成
- 当前阻塞
- 明日第一优先级

## 5. 能力训练地图（你最关心的 Java + AI）

Java 能力点：

- 分层架构与 DDD-lite 建模
- Spring Boot 接口设计与异常体系
- 并发（虚拟线程）与任务编排
- 数据访问与事务边界
- 测试（单测 + 集成测试）与性能基线

AI 能力点：

- Agent 编排与工具调用
- 模型输出结构化（JSON schema）
- 信号融合（规则 + LLM）
- 失败回路（反馈 -> 调整参数）

## 6. 二期扩展门槛（满足后再做）

只有当以下都满足，才进入二期：

- 一期闭环稳定运行两周
- 关键路径测试持续通过
- 端到端演示无需临时修补

二期建议顺序：

1. Kafka 异步化交易信号流
2. order/risk/account 服务独立
3. sentiment/fundamental Agent
4. Redis Vector Sets 记忆系统
5. Neo4j 情景推演引擎

