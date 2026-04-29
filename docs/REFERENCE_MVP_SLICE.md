# Reference MVP Slice

这份参考实现的目标不是做一个收益最优策略，而是提供一条完整、可读、可测试的工程链路：

```text
ClickHouse 日 K 数据
  -> MarketBar 业务模型
  -> 双均线策略信号
  -> 基础风控
  -> 模拟订单
  -> 权益曲线与回测指标
  -> REST API 输出
```

## 代码位置

```text
services/strategy-service/src/main/java/com/quant/strategy/reference/
  application/
    ReferenceBacktestRequest.java
    ReferenceBacktestException.java
    ReferenceMvpBacktestService.java
  domain/
    MarketBar.java
    TradingSignal.java
    SignalType.java
    RiskDecision.java
    SimulatedOrder.java
    OrderSide.java
    EquityPoint.java
    BacktestMetrics.java
    ReferenceBacktestReport.java
  interfaces/
    ReferenceStrategyController.java

services/strategy-service/src/test/java/com/quant/strategy/reference/
  application/
    ReferenceMvpBacktestServiceTest.java
  infrastructure/
    JdbcReferenceBacktestStoreTest.java

web-ui/src/views/
  Dashboard.vue
  Tasks.vue
  TaskDetail.vue

miniprogram-lite/
  pages/dashboard/
  pages/tasks/
  pages/task-detail/
```

## 当前交接状态

已完成：

- Reference MVP 后端 API。
- 内存任务仓储。
- PostgreSQL 任务仓储实现和建表脚本。
- Web 控制台 Dashboard、历史任务、任务详情。
- Harness 新任务质检和历史任务质检。
- technical/orchestrator Agent 最小协同。
- 微信小程序 Lite 原生样板。

仍需本机验收：

- `REFERENCE_STORE_TYPE=jdbc` 真实 PostgreSQL 服务验证。
- ClickHouse 样例数据初始化和连续回测验证。
- 微信开发者工具打开 `miniprogram-lite/` 并调用本机后端。
- PostgreSQL Testcontainers 首次拉取镜像依赖 Docker Hub 网络，网络不稳时可能失败。

## 为什么单独放到 reference 包

现有 `JmaBacktestService` 是已经开始探索的策略服务。Reference slice 不替换它，而是提供一套更适合阅读和学习的纵向样板。

这样做有三个好处：

- 不破坏你当前已有接口和测试。
- 能把“数据、策略、风控、订单、指标”这些边界一次性看清楚。
- 后续可以逐段迁移：先借结构，再替换策略，再接 PostgreSQL 订单表。

## API

### 健康检查

```bash
curl http://localhost:8082/api/reference/health
```

### 快速回测

```bash
curl "http://localhost:8082/api/reference/backtest/quick?stockCode=000001&startDate=2024-03-01&endDate=2024-04-30&fastWindow=5&slowWindow=20"
```

### 完整参数回测

```bash
curl -X POST http://localhost:8082/api/reference/backtest \
  -H "Content-Type: application/json" \
  -d '{
    "stockCode": "000001",
    "startDate": "2024-03-01",
    "endDate": "2024-04-30",
    "fastWindow": 5,
    "slowWindow": 20,
    "initialCash": 100000,
    "maxPositionRatio": 0.8,
    "stopLossRatio": 0.08
  }'
```

返回结构：

```json
{
  "taskId": "uuid",
  "status": "SUCCESS",
  "report": {
    "metrics": {},
    "signals": [],
    "orders": [],
    "equityCurve": []
  },
  "errorMessage": null
}
```

### 查询任务

```bash
curl "http://localhost:8082/api/reference/tasks?limit=20"
curl "http://localhost:8082/api/reference/tasks/{taskId}"
```

### 查询最近信号与订单

```bash
curl http://localhost:8082/api/reference/signals/latest
curl http://localhost:8082/api/reference/orders
```

### 查询质检报告

```bash
curl http://localhost:8082/api/reference/quality/{taskId}
```

## 前端

当前前端已接入 reference API：

```bash
cd web-ui
npm install
npm run dev
```

打开：

```text
http://localhost:5173
```

Vite 会把 `/api` 代理到 `http://localhost:8082`。

## Harness 质检

```bash
python harness/validators/quality_check.py \
  --base-url http://localhost:8082 \
  --stock-code 000001 \
  --start-date 2024-01-01 \
  --end-date 2024-12-31
```

输出固定 JSON：

```json
{
  "status": "passed",
  "taskId": "uuid",
  "backtest": {},
  "quality": {}
}
```

## Agent 最小协同

Technical agent：

```bash
python agents/technical/main.py \
  --base-url http://localhost:8082 \
  --stock-code 000001
```

Orchestrator agent：

```bash
python agents/orchestrator/main.py \
  --base-url http://localhost:8082 \
  --stock-code 000001
```

这两个 Agent 当前不直接连数据库，而是通过 Java Reference API 工作。这样做是为了保持一期主链路单一：行情和交易逻辑集中在 `strategy-service`，Agent 先承担解释、汇总和建议职责。

## 核心设计

### 1. 数据模型隔离

`DailyKlineRecord` 是数据库 record，`MarketBar` 是策略模型。

这层转换看起来多一步，但它能避免策略层直接依赖 ClickHouse 字段细节。以后你从 CSV、AkShare、Kafka 或缓存读行情，策略代码都不用跟着改。

### 2. 信号不等于订单

`TradingSignal` 只代表策略意图：

```text
fast moving average crosses above slow moving average -> BUY signal
fast moving average crosses below slow moving average -> SELL signal
```

`SimulatedOrder` 代表通过风控后的模拟成交。

这个拆分非常关键。真实系统里，策略经常会发出信号，但风控可能拒绝、缩量、延迟或转为人工确认。

### 3. 风控放在策略之后、订单之前

当前风控规则很简单：

- 买入时不超过 `maxPositionRatio`
- 持仓亏损超过 `stopLossRatio` 时允许止损卖出
- 没有持仓时拒绝卖出

后续可以扩展：

- 单票最大仓位
- 最大回撤熔断
- 当日最大交易次数
- 黑名单股票
- 行业集中度

### 4. 回测指标先保持朴素

当前输出：

- `initialCash`
- `finalEquity`
- `totalReturn`
- `maxDrawdown`
- `tradeCount`
- `winningTrades`
- `winRate`

这比一开始就追求复杂指标更好，因为你能先确认账是算对的。后面再加 Sharpe、Calmar、profit factor、年化波动率。

## 后续升级路线

### 第一步：把内存仓储替换为 PostgreSQL

当前参考版使用 `InMemoryReferenceBacktestStore` 保存回测任务。它已经提供了未来 Repository 需要支持的方法：

- `save(task)`
- `findById(taskId)`
- `findRecent(limit)`
- `latestSuccessfulTask()`

当前分支已新增 `JdbcReferenceBacktestStore`，可以通过配置启用 PostgreSQL：

```bash
REFERENCE_STORE_TYPE=jdbc \
REFERENCE_POSTGRES_URL=jdbc:postgresql://localhost:5432/harness_db \
REFERENCE_POSTGRES_USERNAME=harness \
REFERENCE_POSTGRES_PASSWORD=harness123 \
mvn -pl strategy-service spring-boot:run
```

建表脚本：

```text
scripts/create_reference_postgres_tables.sql
services/strategy-service/src/main/resources/reference-postgres-schema.sql
```

PostgreSQL 版本使用这几张表：

- `backtest_task`
- `backtest_signal`
- `simulated_order`
- `equity_curve_point`

### 第二步：把订单落库

新增 PostgreSQL 表：

```sql
CREATE TABLE simulated_orders (
    order_id VARCHAR(80) PRIMARY KEY,
    trade_date DATE NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    price NUMERIC(18, 4) NOT NULL,
    quantity NUMERIC(18, 4) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    risk_reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

然后把 `ReferenceMvpBacktestService.execute(...)` 里生成的 `SimulatedOrder` 交给 `SimulatedOrderRepository.save(...)`。

### 第三步：把策略替换成 JMA

保留这几个结构：

- `TradingSignal`
- `RiskDecision`
- `SimulatedOrder`
- `BacktestMetrics`
- `ReferenceBacktestReport`

只替换 `decideSignal(...)` 的内部逻辑。这样你能明显感觉到“策略可替换，链路不重写”。

### 第四步：加 Agent 解释层

Agent 不要直接下单。建议先让 Agent 做解释：

```text
回测报告 -> Agent 解释 -> 风险提示 -> 人类确认
```

等这条链路稳定，再考虑：

```text
Agent 建议 -> 风控校验 -> 模拟订单
```

## 测试

只跑 reference 单元测试：

```bash
cd services
mvn -pl strategy-service -Dtest=ReferenceMvpBacktestServiceTest test
```

跑策略服务全部测试：

```bash
cd services
mvn -pl strategy-service test
```

如果要跑 ClickHouse 集成测试，需要本地 ClickHouse 可用，或允许 Testcontainers 启动容器。
