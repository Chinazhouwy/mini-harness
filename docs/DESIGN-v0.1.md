# MiniHarness v0.1 开发方案（自进化 Agent Runtime & Studio）

> 分支：`codex/miniharness-self-evolving-v01`（基于 `codex/stock-research-harness` @ f2fad1e）
> 日期：2026-11-18
> 定位：先让「用户输入 → 匹配任务 → Workflow v1 → 事件与轨迹 → 改进候选 → 评测 v2 → 人工审批 → 再次执行」这一最小闭环真正跑通，再谈扩展。

## 一、仓库审计结论（以源码为准）

### 分支关系
| 分支 | 状态 | 结论 |
| --- | --- | --- |
| `main` = `codex/miniharness-legacy` = `codex/long-horizon-harness-skeleton` | 同一历史（cc507c0），旧单体布局 + 面试系统 | 只作历史参考，不改动 |
| `codex/stock-research-harness`（当前基点） | 干净、领先 main；模块已拆分为三学习模块 + core + app | **本次开发基点** |
| `origin/codex/reference-mvp-slice` | 在本分支之上叠加了一个 Python/Java 量化项目，且**删除了三个学习模块** | 与本任务目标（保留学习实验、Java 21+Vue3）冲突，不基于它开发，仅作 UI 参考 |

### 模块现状（实际能力，非 README 规划）
- `miniharness-core`：仅 `ModelConfig`（外部配置读取，不依赖 Spring）→ **已具备框架无关基因，可复用**。
- `miniharness-spring-ai`：`HarnessCore`（单轮 ChatClient 门面 + 三层观测：HTTP 拦截器 / StreamAdvisor / ToolCallingManager 装饰器）、`ModelCreator`（OpenAI 兼容模型工厂）、`FileSearchTool`/`ToolCofig`。**无状态、无 Workflow、无事件，不能冒充 Harness Runtime** → 保留为「模型适配层」，新增 Port 适配器而非重构。
- `app/task-assistant`：空壳 Spring Boot 4.1.0（仅 starter-web + 启动类）→ **复用为应用装配入口**，新增 REST/SSE 控制器与 Bean 装配。
- 三个 `study-*`：真实跑过的学习实验矩阵（超时/预算/重试、上下文策略、registerSchema 挂起回填、权限 7 场景、Graph/checkpoint）。本次以它们为**能力参照**，不破坏、不依赖。

### 复用决策
| 需求 | 来源 | 方式 |
| --- | --- | --- |
| 配置读取 | `miniharness-core/ModelConfig` | 直接复用 |
| 真实模型调用 | `miniharness-spring-ai/ModelCreator+HarnessCore` | 新写 `SpringAiModelPort` 实现 runtime 的 `ModelPort` 接口（依赖方向：spring-ai 适配层 → runtime core） |
| 工具循环参考 | `study/study-spring-ai/ManualSpringAiLoopTest` | 参考其手写循环结构实现 runtime 的确定性 Workflow 节点执行（不引入框架类型） |
| 挂起/回填范式 | `study/study-agentscope/RegisterSchemaTest` | v0.1 只实现「人工审批节点」；挂起恢复协议记为后续任务 |
| 应用装配 | `app/task-assistant` | 原地扩展 |

## 二、新增 Maven 模块

```
miniharness-runtime/        # 新增：框架无关 Harness 内核（只依赖 JDK + jackson-databind 持久化）
studio/                     # 新增：Vue3 + TS + Vite + Pinia + Vue Flow 前端（独立 npm 工程，非 Maven 模块）
```

依赖方向（保持学习模块隔离不变）：

```
study-*（不动） ←（不依赖） miniharness-core ← miniharness-runtime ← { miniharness-spring-ai(适配器), task-assistant(装配) }
```

`miniharness-runtime` 包结构：

| 包 | 职责 | 关键类型 |
| --- | --- | --- |
| `runtime.core` | ID、状态机、异常 | `TaskId/RunId/WorkflowId/NodeId`、`TaskStatus/RunStatus/NodeStatus` 状态机、`HarnessException` |
| `runtime.event` | 事件驱动 | `HarnessEvent`（type/taskId/runId/nodeId/workflow/version/payload/timestamp）、`EventBus`（监听器注册 + SSE 泵送）、12+ 种事件类型 |
| `runtime.task` | Task/Run 状态 | `Task`(长期任务)、`Run`(一次执行)、`Session`(交互)、`TaskStore/RunStore`（内存实现 + JSON 文件可插拔）、显式状态机 |
| `runtime.model` | 模型适配契约 | `ModelPort`（chat + 用量统计 + 调用计数）、`ModelReply`；无模型时用 `NoopModelPort`/脚本化 `ScriptedModelPort` |
| `runtime.tool` | 工具注册与执行 | `ToolRegistry`、`ToolSpec`（输入 schema 描述 + 确定性执行器）、超时/重试/预算 |
| `runtime.workflow` | 流程定义与执行 | `WorkflowDefinition`（name+version 不可变，节点+依赖边）、`WorkflowCompiler`（DAG 校验/环检测/拓扑序）、`WorkflowExecutor`（逐节点执行、失败传播、事件发射）、`WorkflowExecution` |
| `runtime.capability` | 能力路由 | `IntentRouter`：规则 → 活跃任务 → 已发布 Workflow → Skill → 兜底探索；`DecisionModel` 可插拔接口（默认纯规则，不绑定 JEV/LLM） |
| `runtime.skill` | Skill 资产 | `Skill`（触发条件+指令模板+状态）、`SkillStore`、固化为 Workflow 后自动降级 |
| `runtime.evolution` | 自进化 | `ExperienceStore`（轨迹/失败标注）、`TraceAnalyzer`（失败签名挖掘）、`WorkflowCandidate`/`SkillCandidate`、`WorkflowEvaluator`（训练/留出案例双集评测：正确率/错误检出率/成本/耗时/回归数）、`ApprovalService`（评测→批准/拒绝/回滚，批准才改发布指针）、`VersionLedger` |

**为什么这样设计**：Task/Run/WorkflowDefinition/Skill 是显式持久化状态而非聊天历史；模型只在 `ModelPort` 后面（可离线）；进化引擎只能「提案」，发布指针的唯一变更入口是 `ApprovalService`，天然满足「不得未经授权修改生产流程」。

## 三、第一条闭环的数据流（v0.1 主实验）

1. **种子**：`FinanceSeedInstaller` 安装「盈利能力研究」Workflow **v1**（`读取财报 → 计算利润率 → 生成报告`，**故意没有口径验证**）与合成数据集（6 类案例：正常 / 单位不一致 / 报告期不一致 / 缺字段 / 矛盾 / 来源不可确认）。
2. **路由**：用户输入「研究某公司的盈利能力」→ 规则解析出意图+公司 → 命中已发布 Workflow v1 → 创建 Task+Run。
3. **执行 v1**：在单位不一致案例上得出**错误利润率**（把万元当元），Run 成功但结果错误 —— 由案例期望值比对记为失败样本，失败标注（`unit_mismatch`）写入 ExperienceStore。
4. **轨迹分析**：`TraceAnalyzer` 聚合同签名失败（同一节点 + 同一缺陷类型 ≥ 2 次）→ 产出 `WorkflowCandidate` v2（在计算利润率前插入 `口径验证` 节点，附来源 run id、适用条件、状态 PENDING）。
5. **离线评测**：`WorkflowEvaluator` 在**训练集**（3 案例，用于提出改进）与**留出集**（3 案例，独立验证）上分别执行 v1 与 v2，计算正确率、错误检出率、额外工具调用（成本）、耗时、回归失败数。
6. **审批**：v2 评测达标 → API 可批准（`ApprovalService` 发布 v2 为已发布版本，v1 保留可回滚）；同一实验中还有第二个候选 v2-aggressive（激进跳过计算）评测**不达标**，只能拒绝。拒绝不影响发布指针。
7. **再次执行**：发布 v2 后同输入重新执行，结果与事件流均可查。

失败场景覆盖：单位不一致（v1 错、v2 拦）、来源不可确认（v2 halt 节点主动失败）、v2-aggressive 评测不通过。

## 四、多 Agent 路由（最小可插拔）

`ExecutionStrategy` 接口 + 三个实现：
- `DeterministicWorkflowStrategy`：闭环默认，0 模型调用。
- `SingleAgentStrategy`（A）：每节点均可让模型补写结论（记录 ModelCall 次数）。
- `RoutedStrategy`（B）：规则先行，仅不确定性节点问模型。

实验：`StrategyComparisonTest` 用脚本化 `ScriptedModelPort` 统计同一案例下 A/B 的模型调用次数与耗时；真实模型对比提供 `@Tag("live")` 手动入口（环境无模型时明确跳过）。**结论以实测为准，不预设多 Agent 更优。**

## 五、API（task-assistant 新增）

```
POST /api/tasks                 用户输入 → 路由决策 + 建 Task/Run
GET  /api/tasks                 任务列表    GET /api/tasks/{id}   任务详情(含 runs)
POST /api/tasks/{id}/messages   追加信息/质疑(如"利润率不对") → 关联原 run 核查
POST /api/tasks/{id}/runs       指定或默认 workflow 版本执行
GET  /api/runs/{id}             run 详情(节点结果/耗时/异常/重试)
GET  /api/runs/{id}/events      事件列表    GET /api/events/stream  SSE 实时事件
GET  /api/workflows             工作流列表(各版本/发布指针)
GET  /api/workflows/{name}/diff?v1=&v2=  版本差异
GET  /api/evolution/candidates  候选列表    GET /api/evolution/candidates/{id} 候选详情(评测报告)
POST /api/evolution/candidates/{id}/approve | /reject
POST /api/workflows/{name}/rollback   回滚到指定已批准版本
POST /api/chat                  会话消息 → 能力路由(规则优先,无模型则确定性回复)
```

SSE 事件即 `HarnessEvent` 序列化；前端 Workflow Canvas / Run Inspector 由此渲染**真实后端状态**。

## 六、Studio（Vue3）

`studio/`：Vite + Vue Router + Pinia + Vue Flow。页面：Chat、Task Center、Workflow Canvas（读取后端 DAG 与节点状态渲染，第一版不做自由拖拽编排）、Run Inspector（事件时间线/工具调用/耗时/异常/成本）、Evolution Review（v1↔v2 diff + 评测指标 + 批准/拒绝/回滚按钮）。开发期代理 `/api` 到 8080；`npm run build` 产物在 E2E 阶段拷贝进 task-assistant `static/` 以便单 jar 验证。Mock 开关默认关闭且标注。

## 七、持久化

v0.1 单体内存 + JSON 文件落盘（`data/miniharness/`，已 gitignore），接口化（`TaskStore` 等）便于后续替换 SQLite/Postgres。不引入 Redis/MQ/分布式调度。

## 八、测试矩阵（全部可离线运行）

| 层 | 测试 |
| --- | --- |
| 状态机 | Task/Run 非法转换拒绝 |
| WorkflowCompiler | 环检测、悬空边、拓扑序 |
| WorkflowExecutor | 成功路径/节点失败/重试/超时/预算/取消/事件序列 |
| IntentRouter | 规则命中/任务关联/歧义请求确认/兜底 |
| TraceAnalyzer | 失败签名 → 候选提案（带来源 run） |
| WorkflowEvaluator | v1 vs v2 指标、留出集独立、不达标候选被拒 |
| ApprovalService | 批准发布/拒绝不动指针/回滚/重复批准防护 |
| Finance Domain | v1 在缺陷案例产出错误结果、v2 拦截、来源不可确认 halt |
| Strategy | 确定性 0 调用 vs Routed vs SingleAgent 调用次数（脚本化模型） |
| E2E | `@SpringBootTest` 走完整 API 闭环（含批准 v2 + 拒绝 aggressive） |
| live（手动） | SpringAiModelPort 真实模型冒烟 |

## 九、阶段计划与提交粒度

A（本文件）→ B runtime 核心 → C 进化引擎 → D 金融 Domain Pack → B-D 装配 API → E Studio → F E2E → G 复盘报告。每阶段独立提交。

**已知边界（v0.1 明确不做）**：分布式调度/Redis/MQ、任意拖拽编排、AgentScope 式挂起恢复协议全量实现、真实行情 API、JEV 绑定（仅留接口）、置信度校准、模型生成数字直接入账。
