# MiniHarness：Java Agent 与长程任务 Harness 学习项目

本项目用于系统性结对学习 Spring AI、Spring AI Alibaba、AgentScope Java：三者的主要功能、核心 API、底层原理和设计差异都是学习目标。以现有 Spring AI 代码起步，按能力主题阅读官方文档、运行各框架最小例子、追踪关键源码并做同题对照，最后通过 Agent / Harness 综合实践形成设计与选型判断。

学习从具体问题和可运行的小例子出发，关注代码、日志和必要的 Java 并发知识。任务关联型个人 Bot 是综合应用候选；最终应用选用某个框架，不缩减另外两个框架的学习内容。

本 README 后半部分保留了此前长程任务 Harness 和股票研究的历史说明。当前主源码不包含下文描述的旧 `harness` 包；这些说明不能作为当前分支的实现进度，也不代表学习路线被股票场景限定。需要复用时应先核对实际分支与文件。

## Agent 学习现状

- [Reactor 学习测试](src/test/java/com/chinazhouwy/miniharness/agentscope/study/ReactorStudyTest.java)和 [Spring AI 学习测试](src/test/java/com/chinazhouwy/miniharness/springai/study/SpringAiStudyTest.java)是正在整理的练习；[Reactor 复盘](docs/articles/Reactor学习复盘-初稿.md)仍为初稿。
- [手写 Spring AI 工具循环](src/test/java/com/chinazhouwy/miniharness/springai/study/ManualSpringAiLoopTest.java)使用 `ChatModel` 与 `ToolCallingManager`，由自己的代码组织同步循环。固定响应测试验证了两次模型调用和一次工具执行；尚未用真实模型验证该循环。
- [扩展点对照测试](src/test/java/com/chinazhouwy/miniharness/springai/study/AgentHookComparisonTest.java)对照 AgentScope 和 Spring AI，依赖本地模型配置与外部服务；它目前同时引用两套框架。
- 当前 POM 使用 Spring AI 2.0.2 和本机 AgentScope 2.1.0-SNAPSHOT。后者是快照依赖，新环境需要先取得对应版本。
- 已引入 Spring AI Community `spring-ai-agent-utils` 0.13.0，通用工具由调用方显式选择和注册。[接入测试](src/test/java/com/chinazhouwy/miniharness/springai/study/AgentUtilsToolStudyTest.java)用固定工具请求，经 Spring AI Manager 实际执行临时目录内的 Glob 查找；不调用模型和网络。网页抓取、搜索等工具尚未运行验证。[工具包文档](https://github.com/spring-ai-community/spring-ai-agent-utils/tree/v0.13.0/docs/tools)。
- [HarnessCore Demo](src/test/java/com/chinazhouwy/miniharness/HarnessCoreDemo.java)已通过本地配置完成真实模型的流式聊天和 Glob 文件查找示例；它使用 `ChatClient` 内置工具循环，并非上面的手写循环验证。模型配置从被 Git 忽略的 `src/main/resources/openrouter.local.properties` 加载，配置键为 `miniharness.api.key`、`miniharness.base.url`、`miniharness.model`。无本地配置时，当前 `HarnessCore` Bean 初始化也会失败。

### 结对实践安排

**后续模型接手时先阅读：[Agent / Harness 结对学习执行计划](docs/agent-harness-learning-plan.md)。** 该文档统一记录各阶段目的、具体工作、最小交付物、不做事项、验收标准、进度和带教约定；路线调整同步到该文档。

计划第 7～9 节补充了 Spring AI、Spring AI Alibaba、AgentScope Java 的官方文档与版本基线、分阶段 API / 原理表，以及 MCP、Skills、RAG、结构化输出、权限、并发等能力的掌握程度与实施边界。官方文档阅读、源码核对和实际运行验证分别记录，不能互相替代。

执行顺序：真实手写工具循环 → 三框架全景与隔离环境 → 模型/消息/工具/输出契约 → 扩展点与执行边界 → 运行时/并发/流式 → 会话/状态/MCP → 上下文/记忆/RAG/Skills → Harness/权限/恢复 → 工作流/多 Agent/完成验证 → 综合比较与项目实践。

当前下一步是阶段 1：以已有 `HarnessCore` 和 Glob 真实模型 demo 为起点，观察 `ChatClient` 工具调用的中间过程，再与已有手写循环对照。手写循环接真实模型留作后续实验，不作为继续学习的前置条件。计划中的未完成部分不视为已有能力。

每次结对只推进一个小目标，用户参与关键代码与设计；每个专题覆盖功能、API、关键源码、实验与三者差异。阶段 2 建立隔离依赖环境，保留三套最小学习实验；MCP、RAG、Skills、长期记忆、恢复与多 Agent 都安排代表实验。阶段 10 再总结综合项目选型，不要求维护三套完整业务产品。

### 已知依赖冲突

当前两套框架放在同一个 Maven 依赖树中：Spring AI 引入 victools JSON Schema 5.0.0，AgentScope 引入 4.38.0；当前解析结果选中了 5.0.0。已用带参数的 AgentScope 工具注册复现 `NoSuchMethodError`，因此当前混合环境不能认为两边都可正常使用。

在临时独立 Maven 工程中，Spring AI 固定响应循环和 AgentScope 带参数工具注册分别通过。建议后续拆成独立学习模块并使用各自的运行时 classpath；拆分、完整测试迁移和 IDE 配置**尚未完成**。同时引用两套框架的对照测试也需要拆成分别运行的示例。以上验证没有调用真实模型。


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

长程 Harness 的确定性测试本身不需要 API Key、网络或外部服务。仓库中也有需要本地模型配置、外部服务或其他资源的学习测试；不要假设直接运行 `mvn test` 能在任意环境全部通过。运行离线工具循环测试：

```bash
mvn -Dtest=ManualSpringAiLoopTest test
```

验证通用工具接入及原循环：

```bash
mvn -Dtest=AgentUtilsToolStudyTest,ManualSpringAiLoopTest test
```

手动运行真实模型 Demo（需要本地配置及网络，会请求外部模型服务；不在默认 `*Test` 扫描范围内）：

```bash
mvn -Dtest=HarnessCoreDemo test
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

金融代码现在分成两条闭环：旧的 `finance/market` 是用于回放 Harness 的确定性 Fixture 温度计；新的
`finance/research` 是真实数据研究页，不再把 Fixture 伪装成线上数据。

真实研究闭环是：

```text
real market data
  -> paginated response / source evidence
  -> align price / leverage by trade date
  -> factor rows with explicit forward-fill marks
  -> inner model selection / outer time validation
  -> browser line chart + provenance
```

旧的 Fixture 控制台仍可运行：

```bash
mvn -DskipTests compile
mvn dependency:build-classpath -Dmdep.outputFile=target/harness-classpath.txt -Dmdep.includeScope=runtime
java -cp "target/classes:$(cat target/harness-classpath.txt)" \
  com.chinazhouwy.miniharness.finance.market.demo.LeverageDashboardConsole
```

它输出融资余额/流通市值、融资买入强度、融资增长率、融资高点回撤、净偿还压力、波动率、市场宽度，以及
Heat、Clear、Confirmation 评分。这个入口故意保留 Fixture，便于测试 Harness 的可回放性。

### 前端展示页

前端展示的是新的真实数据研究接口：

```bash
# WeStock CLI 需要 npm；root-owned 的用户缓存不可写时可使用临时缓存
NPM_CONFIG_CACHE=/tmp/miniharness-npm-cache mvn spring-boot:run
open http://localhost:8080/
```

页面 API 是 `GET /api/finance/research`，状态字段为 `REAL_DATA`；任何真实源全部不可用时接口报错，
不会降级为 `fixture://`。旧的 `GET /api/finance/leverage` 仍是历史 Fixture API，不是这个页面的数据源。

当前真实样本：

- A 股：上交所融资融券汇总中的 `rzye`（融资余额）作为杠杆输入，除以 `10^12` 转成万亿元；上证综指日 K 线来自 WeStock。
- 港股：腾讯控股 `hk00700` 的 WeStock `ShortRatio` 与日 K 线。
- 美股：Apple `usAAPL` 的 WeStock `ShortRatio` 与日 K 线；卖空报告日之间的缺口以前一个报告值向前填充并单独计数。
- 韩股：暂未接入；WeStock 当前路由只覆盖 A 股、港股、美股，KRX [Data Marketplace 短卖查询](https://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd?menuId=MDC02030201) 在本次验证中要求登录，因此没有把未经验证或不可复现的接口放进真实结果。

WeStock 通过 `npx -y westock-data-skillhub@1.0.5` 调用，不需要 API Key；日 K 线可能延迟，页面展示交易日期。
上交所融资数据和 WeStock 字段、抓取时间、数据日期都会写入页面的“来源证据”。
`GET /api/finance/research` 另外返回 `unavailableMarkets`，记录韩国限制以及本次实时请求中失败的市场，避免把“没有返回”误解为“没有数据”。

方程拟合的是下一交易日对数收益，而不是直接拟合价格水平：

```text
r(t+1) = β0 + β1·z(Lt) + β2·z(ΔLt) + β3·z(M5t) + β4·z(M20t) + ...
```

程序会在 `LEVERAGE_LINEAR`、`LEVERAGE_CURVE`、`LEVERAGE_MOMENTUM` 候选中，先在训练集内部按时间切分选型，
再用完整训练段重拟合，并只在最后 30% 外层验证区间报告 RMSE、零收益基线改善、方向准确率、期末偏差和回撤差异；
外层验证数据不会参与候选选择。预测收益再复合成归一化价格路径，页面用实际收盘线、拟合路径线和验证区间阴影展示。验证 R² 和相关性低时页面照实显示；
这条曲线是解释力实验，不是交易预测或收益承诺。

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

真实研究代码位于 `finance/research`：

```text
finance/research/
├── domain/        标的、交易日观测、数据集、方程结果
├── data/          WeStock CLI、上交所两融 HTTP、真实源编排、测试 Fixture
├── calculation/  候选方程、时间切分、RMSE/R²/相关性
├── service/       缓存和研究查询服务
└── web/           /api/finance/research 与页面 DTO
```

WeStock 日 K 线范围查询按约 240 个自然日分页并按交易日去重，避免单次约 250 条限制静默截断历史。
两融和卖空属于报告日/披露日数据；没有新报告的交易日以前值填充，并在杠杆图上用标记区分，不把填充值伪装成实时数据。

下一条闭环再增加固定股票池、个股基本面、风险事件和两股票对比，不把这些领域概念塞进通用 Harness。

长程 Harness 负责：

```text
任务拆分、依赖、权限、重试、暂停、恢复、证据关联、验证和最终完成判断
```

金融领域任务负责：

```text
股票、指标、财报、公告、估值、风险事件、来源和报告模板
```

此前的股票研究路线提出增加数据快照、更多市场口径和个股研究模型，并让领域能力与通用 Harness 保持分离。
具体演进草图见：[股票研究路线图](docs/stock-research-roadmap.md)。

## 当前边界

当前版本是：

- 单进程、本地 JSON/JSONL
- 单节点串行执行
- 无真实 LLM、无多 Agent、无并行调度
- 无数据库、无分布式恢复
- Demo Artifact 目前保存引用、摘要和描述，不是完整的文件仓库

保留的 Spring Boot 入口是为了未来接入 Spring AI 或数据服务；核心 Harness 不依赖 API Key 才能测试。

原始面试 Demo 和此前的学习代码可在 `codex/miniharness-legacy` 分支查看；当前分支也有上文列出的 Spring AI 和 Reactor 学习代码。
