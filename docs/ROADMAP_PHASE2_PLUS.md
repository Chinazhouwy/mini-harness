# AgenticHarness Phase 2+ Roadmap

版本：2026-04-28  
基线：Phase 1 Reference MVP 已具备一条可运行链路。  
目标：在不丢失可运行性的前提下，把 Reference MVP 逐步演进为更完整的 Java + AI 量化工程系统。

## 总原则

后续阶段采用“替换式演进”，不是一次性推倒重来：

1. 每个阶段都必须保留端到端演示能力。
2. 每次只替换一个关键薄弱点，例如先把内存仓储替换 PostgreSQL，再拆服务。
3. 任何新组件必须能回答三个问题：解决什么问题、如何验证、失败时如何降级。
4. Agent 先做解释和辅助决策，不直接绕开风控下单。

## 当前基线

已经具备：

- Java Reference MVP：回测、信号、风控、模拟订单、指标、质检。
- Web 控制台：触发回测并展示指标、信号、订单、质检建议。
- Harness：通过 HTTP 调用 Reference API 生成质检 JSON。
- Agent：technical + orchestrator 调用 Reference API 生成结构化分析。

主要缺口：

- 回测任务、信号、订单、权益曲线尚未落 PostgreSQL。
- quote/order/risk/account/backtest 尚未形成清晰服务边界。
- Agent 仍是规则编排，没有 LLM 工具调用、记忆、评审闭环。
- Kafka/RocketMQ、Redis、Neo4j、可观测只是基础设施候选，还没进入业务链路。

## Phase 2：持久化与服务内模块化

周期建议：2-3 周  
阶段目标：把 Reference MVP 从“内存演示”升级为“可追踪、可回放、可审计”的本地系统。

### 交付范围

- 用 PostgreSQL 替换 `InMemoryReferenceBacktestStore`。
- 保留 `strategy-service` 单服务，不急着拆微服务。
- 增加正式 Repository、schema migration、集成测试。
- 前端支持历史任务列表、任务详情、订单详情。
- Harness 读取已保存任务做二次质检。

### 建议表结构

```sql
CREATE TABLE backtest_task (
    task_id VARCHAR(64) PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    fast_window INT NOT NULL,
    slow_window INT NOT NULL,
    initial_cash NUMERIC(18, 4) NOT NULL,
    max_position_ratio NUMERIC(8, 4) NOT NULL,
    stop_loss_ratio NUMERIC(8, 4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE backtest_signal (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES backtest_task(task_id),
    trade_date DATE NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    signal_type VARCHAR(10) NOT NULL,
    price NUMERIC(18, 4) NOT NULL,
    fast_average NUMERIC(18, 4),
    slow_average NUMERIC(18, 4),
    reason TEXT
);

CREATE TABLE simulated_order (
    order_id VARCHAR(100) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES backtest_task(task_id),
    trade_date DATE NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    price NUMERIC(18, 4) NOT NULL,
    quantity NUMERIC(18, 4) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    risk_reason TEXT
);

CREATE TABLE equity_curve_point (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES backtest_task(task_id),
    trade_date DATE NOT NULL,
    cash NUMERIC(18, 4) NOT NULL,
    position_quantity NUMERIC(18, 4) NOT NULL,
    close_price NUMERIC(18, 4) NOT NULL,
    total_equity NUMERIC(18, 4) NOT NULL,
    drawdown NUMERIC(18, 4) NOT NULL
);
```

### Java 任务拆分

- 新增 `ReferenceBacktestStore` 接口。
- `InMemoryReferenceBacktestStore` 实现保留给默认演示和单元测试。
- 新增 `JdbcReferenceBacktestStore` 写 PostgreSQL。
- 新增 `ReferencePostgresConfig`，避免和 ClickHouse datasource 混淆。
- 为 `JdbcReferenceBacktestStore` 写 Testcontainers PostgreSQL 集成测试。

### 当前实现状态

`codex/reference-mvp-slice` 分支已完成 Phase 2 的第一批代码：

- `ReferenceBacktestStore` 接口。
- `InMemoryReferenceBacktestStore` 默认实现。
- `JdbcReferenceBacktestStore` PostgreSQL 实现。
- `ReferencePostgresConfig` 与 `reference.postgres.*` 配置。
- `services/strategy-service/src/main/resources/reference-postgres-schema.sql`。
- `scripts/create_reference_postgres_tables.sql`。

启用 PostgreSQL 持久化：

```bash
psql "$REFERENCE_POSTGRES_URL" -f scripts/create_reference_postgres_tables.sql

REFERENCE_STORE_TYPE=jdbc \
REFERENCE_POSTGRES_URL=jdbc:postgresql://localhost:5432/harness_db \
REFERENCE_POSTGRES_USERNAME=harness \
REFERENCE_POSTGRES_PASSWORD=harness123 \
mvn -pl strategy-service spring-boot:run
```

如果不设置 `REFERENCE_STORE_TYPE=jdbc`，系统仍使用内存仓储，适合快速演示。

### 验收标准

- 重启服务后，历史任务仍可查询。
- 同一回测任务可以完整回放：参数、信号、订单、权益曲线、质检结果。
- `mvn -pl strategy-service test` 至少覆盖内存版单测和 PostgreSQL store 集成测试。
- 前端可查看历史任务列表和任务详情。

### 学习重点

- Spring Boot 多数据源配置。
- JDBC 批量写入。
- Repository 接口隔离。
- Testcontainers PostgreSQL。
- 数据库 schema 和业务对象的映射边界。

### Phase 2.1：PostgreSQL Store 完整闭环

目标：让回测任务、信号、订单、权益曲线在 PostgreSQL 中可保存、可查询、可回放。

已完成：

- `ReferenceBacktestStore` 接口。
- `InMemoryReferenceBacktestStore` 默认实现。
- `JdbcReferenceBacktestStore` PostgreSQL 实现。
- PostgreSQL 建表脚本。
- PostgreSQL Testcontainers 集成测试。

剩余任务：

- 在本机 PostgreSQL 中执行 `scripts/create_reference_postgres_tables.sql`。
- 使用 `REFERENCE_STORE_TYPE=jdbc` 跑一次真实服务。
- 调用 `/api/reference/backtest` 后重启服务，再调用 `/api/reference/tasks/{taskId}` 验证数据仍存在。
- 把 Docker Compose 中 PostgreSQL 默认库、用户、密码与 README 示例对齐。

验收命令：

```bash
psql "$REFERENCE_POSTGRES_URL" -f scripts/create_reference_postgres_tables.sql

REFERENCE_STORE_TYPE=jdbc \
REFERENCE_POSTGRES_URL=jdbc:postgresql://localhost:5432/harness_db \
REFERENCE_POSTGRES_USERNAME=harness \
REFERENCE_POSTGRES_PASSWORD=harness123 \
mvn -pl strategy-service spring-boot:run
```

验收标准：

- 服务重启后任务仍可查询。
- `backtest_task`、`backtest_signal`、`simulated_order`、`equity_curve_point` 都有数据。
- 失败任务也能保存错误信息。

### Phase 2.2：历史任务与任务详情

目标：让 Web 控制台从“只看本次运行”升级为“可浏览历史任务”。

当前实现状态：

- 已新增 `GET /api/reference/tasks/latest`。
- 已新增 `GET /api/reference/tasks/{taskId}/signals`。
- 已新增 `GET /api/reference/tasks/{taskId}/orders`。
- 已新增 `GET /api/reference/tasks/{taskId}/equity-curve`。
- 已新增 `GET /api/reference/tasks/{taskId}/quality`。
- 已新增 Web `/tasks` 历史任务列表。
- 已新增 Web `/tasks/:taskId` 任务详情页。

后端任务：

- `GET /api/reference/tasks?limit=20` 增加分页参数：`page`、`size`。
- `GET /api/reference/tasks/{taskId}` 返回完整任务详情。
- `GET /api/reference/tasks/{taskId}/signals` 返回信号列表。
- `GET /api/reference/tasks/{taskId}/orders` 返回订单列表。
- `GET /api/reference/tasks/{taskId}/equity-curve` 返回权益曲线。
- `GET /api/reference/tasks/{taskId}/quality` 返回或重新生成质检结果。

前端页面：

- `/tasks`：历史任务列表。
- `/tasks/:taskId`：任务详情页。
- Dashboard 保留“快速回测 + 最近任务摘要”。
- Market 页面保留链路说明，后续再接行情浏览。

任务列表字段：

- `taskId`
- `stockCode`
- `dateRange`
- `strategy`
- `status`
- `totalReturn`
- `maxDrawdown`
- `tradeCount`
- `createdAt`

任务详情模块：

- 参数卡片。
- 指标卡片。
- 信号表。
- 订单表。
- 权益曲线简图。
- 质检建议。

验收标准：

- 连续跑 3 次回测，列表可看到 3 条任务。
- 点击任意任务可完整查看详情。
- 刷新页面后数据不丢。

### Phase 2.3：Harness 按 taskId 质检

目标：Harness 不只触发新回测，也能复查历史任务。

当前实现状态：

- 已支持 `quality_check.py --task-id <id>`。
- 已支持 `quality_check.py --output report.json`。
- 已支持 `quality_check.py --markdown report.md`。

脚本能力：

- `quality_check.py --task-id <id>`：复查已有任务。
- `quality_check.py --run-new ...`：触发新任务并质检。
- `quality_check.py --output report.json`：输出 JSON 文件。
- `quality_check.py --markdown report.md`：输出 Markdown 报告。

报告结构：

```json
{
  "status": "failed",
  "taskId": "uuid",
  "checks": [
    {"name": "max_drawdown_ceiling", "passed": false}
  ],
  "suggestions": [
    "降低 maxPositionRatio，或收紧 stopLossRatio。"
  ]
}
```

验收标准：

- 不重新跑回测，也能对历史任务生成质检报告。
- 报告能被 Agent 读取。
- 失败时退出码非 0，方便接 CI。

### Phase 2.4：一期演示脚本

目标：把“怎么跑起来”固定成可重复演示。

脚本建议：

- `scripts/demo/start-reference-mvp.sh`
- `scripts/demo/run-reference-backtest.sh`
- `scripts/demo/run-quality-check.sh`
- `scripts/demo/stop-reference-mvp.sh`

演示流程：

1. 启动 ClickHouse/PostgreSQL。
2. 初始化 ClickHouse 行情表和 PostgreSQL 回测表。
3. 启动 `strategy-service`。
4. 启动 `web-ui`。
5. 运行一次回测。
6. 打开前端查看任务、信号、订单和质检建议。
7. 运行 Harness 输出报告。

验收标准：

- 新环境按 README 30 分钟内完成演示。
- 演示失败时能明确定位到基础设施、数据、后端或前端。

## Phase 2.5：Mobile Lite（H5 + 小程序）

周期建议：1-2 周  
阶段目标：在不打断 Phase 2 主线的前提下，做一个轻量移动端入口，用来查看任务结果、Agent 建议和质检状态。

### 定位

Phase 2.5 不是完整移动交易 App，而是“移动查看器 + 快速触发器”。

适合做：

- 查看最近回测任务。
- 查看收益、回撤、交易次数。
- 查看最新信号。
- 查看模拟订单。
- 查看 Agent 建议。
- 查看质检结果。
- 触发快速回测。

不适合一开始做：

- 复杂 K 线交互。
- 参数扫描大表格。
- 多策略矩阵对比。
- 数据导入和服务运维。
- 真实交易下单。

### 技术路线

推荐分三步：

1. H5 移动适配：直接复用 `web-ui`。
2. 小程序 Lite：已采用微信原生小程序，只做任务查看和快速回测。
3. App Lite：只有需要推送、离线缓存、长期盯盘时再考虑 Flutter/React Native。

### Phase 2.5.1：H5 移动适配

目标：让当前 Vue 控制台在手机浏览器可用。

当前实现状态：

- 已新增移动端底部导航。
- 已新增 `/tasks` 历史任务移动卡片列表。
- 已新增 `/tasks/:taskId` 任务详情移动卡片视图。
- Dashboard 已具备移动端单列布局。

前端任务：

- Dashboard 改为移动优先布局。
- 指标卡片单列展示。
- 信号和订单表改为卡片列表。
- 任务详情页支持窄屏。
- 表格横向滚动只作为兜底，不作为主交互。
- 增加移动端底部导航：`首页 / 任务 / 信号 / 我的`。

验收标准：

- iPhone 宽度下无文本重叠。
- 可完成一次快速回测。
- 可查看任务详情、信号、订单、质检建议。

### Phase 2.5.2：小程序 Lite

目标：提供一个轻量微信小程序入口。

当前实现状态：

- 已新增 `miniprogram-lite/` 微信原生小程序样板。
- 已新增 `utils/api.ts` 对接 Reference API。
- 已新增 `utils/types.ts` 维护任务、指标、信号、订单、质检类型。
- 已新增首页 `pages/dashboard`：健康检查、快速回测、最新任务。
- 已新增任务页 `pages/tasks`：历史任务列表。
- 已新增详情页 `pages/task-detail`：指标、信号、订单、质检建议。
- 已新增 `miniprogram-lite/README.md` 说明本地开发、真机调试和 HTTPS 注意事项。

页面建议：

```text
pages/
  dashboard/
    dashboard
  tasks/
    tasks
  task-detail/
    task-detail
```

接口依赖：

- `GET /api/reference/tasks`
- `GET /api/reference/tasks/{taskId}`
- `GET /api/reference/tasks/{taskId}/signals`
- `GET /api/reference/tasks/{taskId}/orders`
- `GET /api/reference/tasks/{taskId}/quality`
- `POST /api/reference/backtest`

小程序数据模型：

```json
{
  "taskId": "uuid",
  "stockCode": "000001",
  "totalReturn": 0.12,
  "maxDrawdown": 0.08,
  "tradeCount": 4,
  "qualityPassed": true,
  "latestSignal": "BUY"
}
```

注意事项：

- 小程序必须使用 HTTPS 域名，本地开发可先用开发者工具代理。
- 不在小程序里保存敏感凭据。
- 小程序只展示模拟结果，不接真实交易。
- 后端需要加简单鉴权，否则公网暴露风险很高。
- 当前默认 `API_BASE_URL=http://127.0.0.1:8082`，真机调试时需要改为局域网 IP 或 HTTPS 域名。

验收标准：

- 微信开发者工具可打开 `miniprogram-lite/`。
- 小程序首页能展示最近任务。
- 任务详情能展示指标、信号、订单、质检建议。
- 快速回测能创建新任务。
- 正式发布前完成 HTTPS 域名、鉴权和小程序后台 request 合法域名配置。

### Phase 2.5.3：移动提醒

目标：让移动端具备“结果提醒”能力，但不引入复杂实时系统。

优先方案：

- Web/H5：轮询最近任务状态。
- 小程序：订阅消息或服务通知，后续再做。
- 后端：先提供 `GET /api/reference/tasks/latest`。

提醒场景：

- 回测完成。
- 质检失败。
- 最新信号为 BUY/SELL。
- Agent 给出 REVIEW_PARAMETERS。

验收标准：

- 移动端能看到最近一次任务状态变化。
- 质检失败有明显提示。

### Phase 2.5 暂不做

- 真实交易。
- 用户体系。
- 支付、订阅、商业化。
- 推送复杂编排。
- 原生性能优化。

## Phase 3：正式策略与风控引擎

周期建议：3-4 周  
阶段目标：从“双均线参考策略”升级为“多策略、多风控规则、可配置回测”的策略实验平台。

### 交付范围

- 策略接口标准化：`StrategyEngine`。
- 风控接口标准化：`RiskRule` + `RiskRuleEngine`。
- 引入 JMA 策略、双均线策略、突破策略三个策略实现。
- 参数扫描：同一股票可批量跑多个参数组合。
- 增加更完整指标：年化收益、年化波动、Sharpe、Calmar、profit factor。

### 建议接口

```java
public interface StrategyEngine {
    String name();
    List<TradingSignal> generateSignals(List<MarketBar> bars, StrategyParameters parameters);
}

public interface RiskRule {
    String code();
    RiskDecision evaluate(RiskContext context);
}
```

### 风控规则清单

- 最大仓位比例。
- 单票最大亏损。
- 单日最大亏损。
- 最大连续亏损次数。
- 最大回撤熔断。
- 黑名单股票。
- 最低成交额过滤。

### 验收标准

- 同一批行情可运行至少 3 种策略。
- 每条订单都能追踪触发信号和风控决策。
- 参数扫描结果可排序，并能导出 JSON/CSV。
- 至少 10 个策略/风控单元测试。

### 学习重点

- Java 策略模式、组合模式。
- BigDecimal 金额计算。
- 参数对象设计。
- 可测试的规则引擎。
- 指标计算的边界条件。

## Phase 4：Agent 工程化

周期建议：3-5 周  
阶段目标：把 Agent 从“脚本调用 API”升级为“可解释、可审计、可反馈”的分析助手。

### 交付范围

- technical agent：解释技术信号、参数表现和风险。
- risk reviewer agent：解释风控拒绝原因和参数建议。
- orchestrator：汇总多个 Agent 输出，形成结构化建议。
- 引入 LLM，但必须要求 JSON schema 输出。
- 保存 Agent 运行记录到 PostgreSQL 或 MinIO。

### Agent 输出结构

```json
{
  "task_id": "uuid",
  "agent": "technical",
  "verdict": "review",
  "confidence": 0.72,
  "observations": [
    "fastWindow 过短导致信号偏密"
  ],
  "suggestions": [
    {
      "action": "increase_slow_window",
      "reason": "降低震荡区间交易频率"
    }
  ]
}
```

### 工具调用边界

Agent 可以调用：

- 查询回测任务。
- 查询信号和订单。
- 查询质检报告。
- 触发新的参数扫描。

Agent 不允许直接：

- 绕开风控生成订单。
- 修改历史任务结果。
- 删除交易记录。

### 验收标准

- 同一回测任务可以生成 technical/risk/orchestrator 三类报告。
- LLM 输出解析失败时有降级策略。
- Agent 建议能被 Harness 记录并复查。
- 至少 5 条失败样例可复现。

### 学习重点

- LLM 结构化输出。
- Tool calling。
- Prompt 版本管理。
- Agent 记忆与审计。
- 失败恢复和超时控制。

## Phase 5：事件驱动与服务拆分

周期建议：4-6 周  
阶段目标：把单服务内同步链路拆成清晰的事件流，为后续扩展和微服务拆分做准备。

### 拆分顺序

不要一口气拆完。建议顺序：

1. `quote-service`：行情导入、行情查询。
2. `backtest-service`：回测任务编排、指标计算。
3. `risk-service`：风控规则和风控审计。
4. `order-service`：模拟订单和订单状态。
5. `strategy-service`：只负责策略信号生成。

### Kafka Topic 建议

```text
market.daily-kline.imported
strategy.signal.generated
risk.decision.created
order.simulated.created
backtest.task.completed
agent.review.created
```

### 事件结构建议

```json
{
  "eventId": "uuid",
  "eventType": "strategy.signal.generated",
  "occurredAt": "2026-04-28T10:00:00",
  "traceId": "uuid",
  "payload": {}
}
```

### RocketMQ 的位置

一期和二期不建议同时维护 Kafka + RocketMQ 两套业务事件。可以把 RocketMQ 放到后续专题：

- 延迟任务。
- 订单状态超时检查。
- 风控补偿任务。

### 验收标准

- 每个 topic 都有生产者、消费者和失败重试策略。
- 事件具备 traceId，可串起一次回测全链路。
- 任意消费者失败后可恢复消费，不导致任务静默丢失。
- 本地 docker compose 可启动最小事件链路。

### 学习重点

- 事件建模。
- 幂等消费。
- 重试和死信。
- 分布式 trace。
- 服务边界和数据所有权。

## Phase 6：记忆、知识图谱与仿真

周期建议：长期探索  
阶段目标：把系统从“回测工具”升级成“策略研究助手”。

### Redis Vector Sets / 向量记忆

用途：

- 保存历史 Agent 结论。
- 检索相似失败案例。
- 检索相似市场状态。
- 复用调参经验。

验收标准：

- 输入一个失败任务，能找出 3 个相似历史任务。
- Agent 建议能引用历史案例，而不是只凭当前结果。

### Neo4j 知识图谱

用途：

- 股票 -> 行业 -> 概念 -> 事件 -> 策略表现。
- 策略 -> 参数 -> 市场阶段 -> 风险模式。
- Agent 结论之间的因果关系。

验收标准：

- 能查询某行业下策略表现最差的参数组合。
- 能查询某类风险事件影响过哪些策略任务。

### 情景仿真 simulation

用途：

- 模拟市场冲击。
- 模拟成交滑点。
- 模拟风控熔断。
- 模拟多 Agent 分歧。

验收标准：

- 给定一个回测任务，可以生成至少 3 个压力情景。
- 每个情景输出收益、回撤、订单变化和 Agent 解释。

## Phase 7：生产化与可观测

周期建议：长期  
阶段目标：让系统具备持续运行、定位问题和演示稳定性的能力。

### 可观测

- Prometheus 指标：请求耗时、回测耗时、任务成功率、Agent 失败率。
- Grafana 面板：服务健康、任务吞吐、失败原因。
- SkyWalking trace：一次回测跨服务调用链。
- 结构化日志：JSON 日志 + traceId。

### 部署

- Docker Compose：本地演示环境。
- K8s：后续生产练习环境。
- GitHub Actions：测试、构建、镜像发布。

### 验收标准

- 任意任务失败能在 5 分钟内定位到服务、接口、错误原因。
- CI 能跑 Java 测试、Python 语法检查、前端构建。
- 本地演示环境一条命令启动。

## 总里程碑看板

| 阶段 | 名称 | 状态 | 关键产物 |
|---|---|---|---|
| Phase 1 | Reference MVP | 已完成参考版 | Reference API、Web 控制台、Harness、Agent 最小协同 |
| Phase 2 | 持久化与模块化 | 下一步 | PostgreSQL store、历史任务、任务回放 |
| Phase 3 | 策略与风控引擎 | 待开始 | 多策略、多风控、参数扫描 |
| Phase 4 | Agent 工程化 | 待开始 | LLM 解释、Agent 审计、反馈闭环 |
| Phase 5 | 事件驱动与拆服务 | 待开始 | Kafka topic、服务边界、幂等消费 |
| Phase 6 | 记忆与仿真 | 探索 | 向量记忆、Neo4j、压力情景 |
| Phase 7 | 生产化与可观测 | 探索 | CI、监控、trace、K8s |

## 推荐执行顺序

近期只做这 5 件事：

1. Phase 2.1：PostgreSQL store 替换内存仓储。
2. Phase 2.2：前端历史任务列表和任务详情。
3. Phase 3.1：抽象 `StrategyEngine` 和 `RiskRule`。
4. Phase 3.2：把现有 JMA 接到统一策略接口。
5. Phase 4.1：让 Agent 对一个已保存任务生成结构化解释。

这五件做完之后，再考虑 Kafka、Redis Vector、Neo4j 和 K8s。
