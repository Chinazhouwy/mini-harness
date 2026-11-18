# MiniHarness v0.1 技术报告（工程复盘）

> 分支：`codex/miniharness-self-evolving-v01` ｜ 日期：2026-11-18
> 回答的核心问题：**一个 Agent 系统能否通过持续执行任务、积累真实经验、提出改进并进行独立验证，把原本依赖开放式推理的任务逐步转化为更可靠、成本更低的可复用能力？**

## 一、结论（先说答案）

**在本次实验的边界内，答案是被证据支持的「是，且成本转化路径成立」。**

金融研究 Domain Pack 的受控实验显示：
- v1（无校验流程）在单位不一致数据上「执行成功」但产出错误净利率（0.100 vs 期望 0.11）——证明开放式流程不设防时，成功状态 ≠ 结果正确；
- 轨迹分析器从失败经验中自动提出 Workflow v2（在计算前插入口径验证节点），候选携带来源证据（失败 Run/评测案例 ID）；
- 同一受控案例集（训练 3 + 留出 4，互不重叠）真实执行对比：**留出集正确率 0.25 → 1.0，错误检出率 0 → 1.0，零回归，额外工具调用 0**（验证节点是确定性 Java 逻辑，零模型成本）；
- 过激对照候选（强制校验可选字段）被门禁自动拦下（留出集回归）并拒绝——证明门禁能防止「看似改进实则更差」的提案；
- 批准发布 v2 后，同一缺陷输入从「成功+错误结果」变为「结构化拒绝 UNIT_MISMATCH」——版本行为真实不同，且 v1 保留、可回滚。

这里最重要的结构性事实是：**改进来自流程结构（插入验证节点），而不是更长的提示词**。被验证的机制是把「数据口径检查」这类原本可能每次都交给模型的判断，固化为零成本、可单测、确定性执行的节点。

## 二、交付范围（对照任务书十条验收）

| # | 验收项 | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | 可启动的 Java 后端 | ✅ | `java -jar examples/task-assistant/target/task-assistant-0.1.0.jar`，UI+API 单 jar 提供 |
| 2 | 可启动的 Vue3 前端 | ✅ | `studio/`（npm run dev / build）；build 产物已随 jar 提供，浏览器实测 5 页 |
| 3 | 框架无关 Harness 内核 | ✅ | `miniharness-runtime` 只依赖 JDK+Jackson，无 Spring AI/AgentScope/Alibaba 类型 |
| 4 | 真实执行的金融研究 Workflow | ✅ | profitability-research v1/v2，节点确定性执行，工具 `finance.load_statements` |
| 5 | 执行事件与任务状态记录 | ✅ | 34 类事件 + EventLog 回放 + SSE；Task/Run/Session 状态机 |
| 6 | 失败经验 → Workflow v2 候选实验 | ✅ | TraceAnalyzer 规则 A（口径失败聚类→插入验证节点），候选带来源 Run/案例 |
| 7 | 真实运行的新旧版本对比评测 | ✅ | CaseRunner + WorkflowEvaluator：训练/留出双集，5 条门禁，指标全部来自真实执行 |
| 8 | 可审查/批准/拒绝的版本界面 | ✅ | Evolution Review：差异、原因、评测对比、逐案例结果、批准/拒绝/回滚（后端校验） |
| 9 | 端到端自动化验证 | ✅ | `EndToEndClosedLoopTest`（10 步全 HTTP）+ 50 个离线测试全绿 |
| 10 | 说明已实现/仍是概念的技术报告 | ✅ | 本文档 |

## 三、架构与取舍

### 分层与依赖方向
```
study-*（三个学习模块，未改动）        ← 不被任何模块依赖
miniharness-core（配置读取）          ← 复用
miniharness-runtime（框架无关内核）    ← 只依赖 JDK+Jackson
miniharness-spring-ai（适配层）        ← 新增 SpringAiModelPort；依赖方向：适配层→内核
examples/task-assistant（装配+API）    ← 组装引擎 + REST/SSE
studio/（Vue3，独立 npm 工程）         ← 只通过 HTTP/SSE 通信
```

- **为什么内核不依赖框架**：状态机、事件、DAG 编译执行、评测与审批都是确定性逻辑，模型只是可选端口（ModelPort）。离线模式下 NoopModelPort 使任何模型调用显式失败——这保证了整个闭环（路由→执行→评测→审批）100% 可离线验证、可重复。
- **为什么流程差异体现在结构而不是代码**：v1 与 v2 的 `ComputeMarginsHandler` Java 逻辑完全相同；行为差异只来自 v2 插入了验证节点。这让「版本间差异」可以被编译器静态校验、被 diff 接口展示、被评测量化。
- **为什么审批是唯一发布通道**：WorkflowRegistry 是唯一持有发布指针的组件，ApprovalService 是唯一调用 publish 的业务入口；批准必须 `EVALUATED_PASS`。候选/回滚/拒绝全部留审计事件。
- **为什么评测用外部期望**：EvalCase 的期望值来自案例定义（人写的外部事实），绝不采信被测流程自己的输出。留出集在提出改进时不参与，防止「在用于改进的案例上评估自己」。

### 复用与自研边界
| 复用 | 自研（MiniHarness 内核） |
| --- | --- |
| ModelConfig 配置读取 | Task/Run/Node 状态机、RunBudget |
| Spring AI（仅适配层） | 事件总线/事件日志、DAG 编译器（Kahn+环检测）、执行器（超时/重试/预算/取消/跳过传播） |
| study-* 作为能力参照（不动） | 意图路由（规则→任务→Workflow→Skill→兜底）、经验/候选/评测/审批（进化生命周期） |
| | 合成金融数据集与确定性金融节点 |

## 四、关键机制的行为边界（哪些可验证，哪些依赖模型）

| 行为 | 可验证性 |
| --- | --- |
| 状态机转换 | 单元测试覆盖非法转换拒绝 |
| DAG 编译（环/悬空边/未知类型） | 编译期拒绝，测试覆盖 |
| 节点执行（超时/重试/预算/取消） | 单元测试覆盖 |
| 意图路由与歧义确认 | 规则路径单元测试；歧义时绝不自动合并，请求确认 |
| 失败签名 → 候选提案 | 基于持久化经验记录，测试覆盖规则 A（口径失败→插验证节点）与规则 B（运行过但从未成功的节点→删除并重连边，SKIPPED 下游不算死节点；含阈值不足不提案） |
| 评测指标与门禁 | 真实执行计算，测试断言具体数值（0.25→1.0 等） |
| 批准/拒绝/回滚 | 后端状态校验，测试覆盖（未评测不许批准、拒绝不动指针、回滚目标必须曾发布） |
| 执行策略对比（A/B/C） | **离线已实现**：`runtime.strategy.ExecutionStrategy` 最小可插拔接口 + 确定性基线/单Agent/规则路由三实现 + `StrategyComparisonTest`。真实测量（金融任务 case-clean，脚本化模型）：A 组 3 次模型调用（每节点 1 次）vs B 组 0 次 vs 基线 0 次；A 组提示词成本 >0，B/基线 0；工具调用一致；输出一致且正确（模型回复只进事件审计，不触碰确定性计算）。无模型环境下 B 组照常完成、A 组显式失败。**真实模型的质量对比仍属 live 入口任务**（脚本模型无网络延迟，离线耗时差不显著） |
| Agent 自主探索（兜底路由） | **v0.1 为概念**：路由到 AGENT_EXPLORE 后，无模型环境返回固定说明，未实现开放探索 |
| 真实模型参与的执行 | **需 live 环境**：SpringAiModelPort 已实现并接线（auto 模式），但本轮未跑真实模型验证 |
| JEV/轻量分类器接入 | 仅有 DecisionModel 接口 + 一个可选的模型实现；未绑定、未调参 |

## 五、已发现并修复的真实缺陷（开发过程中的工程事实）

1. ScriptedModelPort 双重 poll bug（脚本化模型会跳过回复）——测试前修复；
2. 事件总线具名 SSE 事件导致前端 `onmessage` 收不到——改为默认消息事件；
3. SSE 建连不冲刷响应头导致浏览器永远 CONNECTING——订阅即发握手事件；
4. SPA 深链（/chat 等）刷新 404——加 forward 回退；
5. 评测经验无 RunId 导致候选来源显示 unknown——补 eval-case 任务标识，保证证据链可追溯；
6. JUnit 5 默认每方法新实例导致 E2E 字段丢失——PER_CLASS 生命周期；
7. InMemoryTaskStore 用 ConcurrentHashMap 导致路由候选顺序不稳定（决策模型实验不可复现）——换 LinkedHashSet 语义。

这些缺陷均由测试或浏览器实测暴露，修复后回归全绿。

## 六、技术债与边界（如实记录）

1. **持久化是内存态**：重启丢状态（Workflow 账本/任务/经验均内存）。接口已抽象（TaskStore/RunStore/ExperienceStore），接 SQLite/文件是后续任务，本轮未做——明确标注，不假装持久。
2. **单体内单线程 Run 池**：Run 串行执行（单线程 runPool）。并发多任务会排队；分布式/多 worker 明确延期。
3. **Workflow Canvas 只读可视化**：分层布局 + Run 状态叠加，节点样式朴素（Vue Flow 默认节点），不支持拖拽编排——符合第一版定位。
4. **Agent 探索/子 Agent/Reviewer 未实现**：任务书第五节的「最小可插拔接口 + A/B 离线对比实验」已完成（`ExecutionStrategy` 接口、确定性基线/单Agent/规则路由三实现、`StrategyComparisonTest` 真实测量调用次数/成本/耗时）；**尚未实现**的是主 Agent + 子 Agent 分工（C 组）与 Reviewer 角色，以及真实模型下的质量对比（脚本化模型只能可靠对比调度开销，输出质量在离线下三策略必然一致）——留作 live 入口任务。
5. **意图规则集小**：当前覆盖「研究X的盈利能力/利润率」与「质疑类消息」两类；新领域需扩展 RuleIntentExtractor 与 Domain Pack。
6. **合成数据的局限**：口径缺陷是注入的、模式固定；真实世界脏数据形态远多于此。TraceAnalyzer 的规则 A 只对「数据口径类失败」有效，不能泛化到所有缺陷类型（失败签名规则显式列出了支持 kinds）。
7. **token 统计为字符估算**：Spring AI usage 字段在网关下可得性不一，仅作成本观察。
8. **git push 未完成（外部阻塞，已按多次重试不可达关闭）**：2026-11-18 开发与收尾期间共发起 **11+ 次推送尝试，跨约 2 小时**（直连、经本机代理 7890、7897 端口、等待 90/120/60 秒后重试），错误签名一致：`LibreSSL SSL_connect: SSL_ERROR_SYSCALL in connection to github.com:443`，连通性探测 `https://github.com/` 始终返回 000（TLS 握手被中断，属环境级网络故障而非认证或仓库问题）。**9 个提交完整保存在本地分支 `codex/miniharness-self-evolving-v01`**（基于 `codex/stock-research-harness`，工作树干净）。网络恢复后执行以下命令即可完成推送，无需任何额外操作：
   ```bash
   cd /Users/chinazhouwy/doc/MiniHarness
   git push -u origin codex/miniharness-self-evolving-v01
   ```

## 七、运行方法

```bash
# 后端（离线确定性模式，UI+API 单 jar）
mvn clean package -DskipTests -pl examples/task-assistant -am
MINIHARNESS_MODEL_MODE=offline java -jar examples/task-assistant/target/task-assistant-0.1.0.jar
# 打开 http://127.0.0.1:8080/ （auto 模式且配置了 config/openrouter.local.properties 时自动接真实模型）

# 前端开发模式
cd studio && npm install && npm run dev   # http://localhost:5173，/api 代理到 8080

# 全部离线测试（50 个，不含 live/manual）
mvn test

# 端到端闭环（10 步，全 HTTP）
mvn -pl examples/task-assistant -am test -Dtest=EndToEndClosedLoopTest
```

UI 内的最短闭环路径：Chat 输入「研究华夏制造的盈利能力，分析利润率变化」→ 自动执行；Evolution Review 点「运行自进化实验」→ 查看两候选评测 → 批准 v2 → Chat 重新发起（缺陷输入被拒）→ Workflow Canvas 对比 v1/v2。

## 八、下一步建议（按优先级）

1. **持久化落地**：WorkflowRegistry/ExperienceStore/TaskStore 接 SQLite（现有接口已是缝隙），让经验跨重启累积——这是「持续进化」的前提。
2. **真实模型对比实验（A/B/C）**：ExecutionStrategy 接口已留，在 auto 模式下用同一受控案例跑单 Agent vs 规则路由 vs 子 Agent，比较质量/调用次数/成本/时延，数据落 ExperienceStore。
3. **缺陷签名扩展**：口径类之外，加入「数值异常检测」（如利润率>1 或 <0 的显著性检查）等可确定性化的判断规则，扩大「LLM 判断→Java 逻辑」的转化面。
4. **Skill 生命周期完整化**：目前有降级与沉淀提案，缺「失效删除/合并」的自动化清理（需使用统计）。
5. **挂起-恢复协议**（借鉴 AgentScope registerSchema）：人工审批节点目前在流程外（发布指针），流程内的人工确认节点是下一步。
6. **推送与 CI**：网络恢复后推送分支；把 `mvn test` + E2E 挂 CI。
