# MiniHarness：长程任务与股票研究 Harness

这个仓库现在专门研究一件事：如何让一个可以运行很久、可以暂停恢复、可以验证结果的任务可靠地推进。

它不再以面试 Demo 或普通 Chat 为主线。当前先用不调用模型的确定性任务，把长程任务运行时的骨架跑通；
后续再把这个骨架演变成一个有真实使用价值的股票/金融分析框架。

## 当前闭环

```text
Mission
  -> Task Graph
  -> Task Run / Attempt
  -> Artifact / Evidence
  -> Verifier
  -> Checkpoint + Event Replay
  -> Retry / Block / Complete
```

当前 Demo 任务图：

```text
prepare -> execute -> verify -> report
```

核心代码位于 [`harness`](src/main/java/com/chinazhouwy/miniharness/harness/) 下的功能模块，入口是
[`LongTaskHarnessConsole`](src/main/java/com/chinazhouwy/miniharness/harness/demo/LongTaskHarnessConsole.java)。

## 当前实现

- `Mission`：一次长期任务的目标、验收标准和生命周期。
- `TaskNode`：任务图节点、依赖、最大尝试次数和状态。
- `TaskAttempt`：一次执行尝试及其结果、证据和产物。
- `ArtifactRef`：后续任务可以引用的产物。
- `HarnessEvent`：不可变运行事件，保存为 JSONL。
- `Checkpoint`：可恢复快照，保存为 JSON。
- `HarnessOrchestrator`：单节点串行调度、验证、重试、阻塞和完成判断。
- `FileHarnessStateStore`：本地文件存储，snapshot 使用临时文件后原子移动。

包结构按职责划分：

```text
harness/
├── domain/        Mission、Task、Artifact、状态转换
├── event/         HarnessEvent 和事件 payload
├── execution/     TaskExecutor 接口、执行上下文
├── verification/  TaskVerifier 接口、Verdict
├── persistence/   JSON snapshot、JSONL event、replay
├── runtime/       HarnessOrchestrator、RunResult
└── demo/          DemoMissionFactory、DeterministicTaskExecutor、DemoTaskVerifier、控制台入口
```

测试替身（`AlwaysFailTaskExecutor`、`FailOnceTaskExecutor`）放在 `src/test` 的 `execution` 包，只服务于测试，
不参与主源码分层。

任务状态和事件状态不依赖聊天历史。`ChatMemory` 将来最多只作为某个 Task 内部的模型上下文，不能代替
业务状态、事件日志或研究证据。

## 运行

Harness 测试不需要 API Key、网络或外部服务：

```bash
mvn test
```

运行确定性控制台：

```bash
mvn -DskipTests compile
mvn dependency:build-classpath -Dmdep.outputFile=target/harness-classpath.txt -Dmdep.includeScope=runtime
java -cp "target/classes:$(cat target/harness-classpath.txt)" \
  com.chinazhouwy.miniharness.harness.demo.LongTaskHarnessConsole
```

控制台只写入当前工作目录下的 `data/harness/`，不会调用真实 LLM。

更完整的设计说明见：[长程任务 Harness v0 骨架](docs/long-horizon-harness-skeleton.md)。

## 演变为股票金融分析框架

第一条已经落地的金融闭环是不做预测和自动交易的“公开可观测 A 股杠杆温度计”：

```text
collect-market-data
  -> calculate-metrics
  -> score-market-state
  -> write-report
```

运行它：

```bash
mvn -DskipTests compile
mvn dependency:build-classpath -Dmdep.outputFile=target/harness-classpath.txt -Dmdep.includeScope=runtime
java -cp "target/classes:$(cat target/harness-classpath.txt)" \
  com.chinazhouwy.miniharness.finance.market.demo.LeverageDashboardConsole
```

当前使用确定性 Fixture，输出融资余额/流通市值、融资买入强度、融资增长率、融资高点回撤、净偿还压力、波动率、市场宽度，以及 Heat、Clear、Confirmation 评分。报告明确区分计算证据、数据来源和风险限制。

### 前端展示页

同一套后端计算结果也提供了一个只读仪表盘，用于查看当前状态、历史轨迹、任务链和来源证据：

```bash
mvn spring-boot:run
open http://localhost:8080/
```

页面 API 是 `GET /api/finance/leverage`。当前页面使用确定性 Fixture，不需要 API Key，也不发出买卖指令。

金融领域代码位于 `finance/market`，按职责拆分：

```text
finance/market/
├── domain/        原始观测、指标、评分、来源证据
├── data/          Fixture 和未来的交易所数据适配器
├── calculation/  指标、滚动分位数、状态评分
├── persistence/   金融 Artifact 文件
├── execution/     可注入 Harness 的金融任务执行器
├── verification/  Artifact 和报告结构验证
├── report/        Markdown 报告
├── workflow/      金融任务图
├── web/           只读查询 API 和前端展示 DTO
└── demo/          本地可运行入口
```

下一条闭环再增加固定股票池、个股基本面、风险事件和两股票对比，不把这些领域概念塞进通用 Harness。

长程 Harness 负责：

```text
任务拆分、依赖、权限、重试、暂停、恢复、证据关联、验证和最终完成判断
```

金融领域任务负责：

```text
股票、指标、财报、公告、估值、风险事件、来源和报告模板
```

因此下一步应该是把 Fixture 替换成只读数据适配器，再增加个股研究领域模型，而不是先把模型调用、多 Agent
或并行调度塞进核心运行时。具体演进草图见：[股票研究路线图](docs/stock-research-roadmap.md)。

## 当前边界

当前版本是：

- 单进程、本地 JSON/JSONL
- 单节点串行执行
- 无真实 LLM、无多 Agent、无并行调度
- 无数据库、无分布式恢复
- Demo Artifact 目前保存引用、摘要和描述，不是完整的文件仓库

保留的 Spring Boot 入口是为了未来接入 Spring AI 或数据服务；核心 Harness 不依赖 API Key 才能测试。

原始面试 Demo 和 Spring AI 学习代码保存在分支：`codex/miniharness-legacy`。
