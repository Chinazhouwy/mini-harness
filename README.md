# MiniHarness：三个 Agent 框架的隔离学习与 Harness 实践

系统学习 Spring AI、Spring AI Alibaba、AgentScope Java 的功能、API、底层原理与差异，逐步形成自己的 Harness。每次推进一个具体问题，用代码、日志和运行结果对照；不把阅读源码、模拟模型和真实模型验证混为一谈。

## 模块与边界

```text
mini-harness/
├── pom.xml                         # 聚合构建与通用插件，不引入任何 Agent 框架
├── config/                         # 本地模型配置，真实凭据不入库、不进 JAR
├── miniharness-core/               # 框架无关的公共代码，目前只有配置读取
├── miniharness-spring-ai/          # 现有 HarnessCore、模型创建、工具与日志
├── study-spring-ai/                # Spring AI 学习测试与 demo
├── study-agentscope/               # AgentScope 学习测试与 demo
├── study-spring-ai-alibaba/         # Alibaba ReactAgent 学习测试与 demo
└── examples/
    └── task-assistant/             # Spring Boot 应用装配入口，目前仅有启动类
```

| 模块 | 依赖与职责 |
| --- | --- |
| `miniharness-core` | 主代码只依赖 JDK。后续确有需求时再提取 runtime、context、tool、session、event 契约，当前不创建空实现。 |
| `miniharness-spring-ai` | 依赖 core、Spring AI **2.0.2**、agent-utils **0.13.0**；承接现有 Spring AI 实现。`HarnessCore` 依赖 Spring AI，暂不当成通用核心。 |
| `study-spring-ai` | 依赖 Spring AI 适配模块；保留 `HarnessCoreDemo`、`HarnessCoreDemoTest`、手写循环及 Spring AI 学习测试。 |
| `study-agentscope` | 依赖 core、AgentScope **2.1.0-SNAPSHOT**；沿用其 Schema 4.38.0，不再替换 Surefire 运行类路径。新环境需要先取得该快照依赖。 |
| `study-spring-ai-alibaba` | 依赖 core、Alibaba **1.1.2.2** 和 Spring AI **1.1.2**，本模块单独管理 Jackson 2.19.4。 |
| `task-assistant` | 只装配 `miniharness-spring-ai`。旧金融静态页已清理；目前仅有启动入口，任务助手业务尚未实现。 |

三个 `study-*` 模块彼此不依赖，也不被应用依赖。父工程不继承 Spring Boot 父 POM、不导入 Spring AI BOM；框架版本由使用它的模块自行管理。

Maven 聚合构建不等于把所有模块合并到一个运行类路径。分别运行各模块测试，应用只选择一套实现，才能同时隔离版本冲突与 Bean 扫描。不要在 `task-assistant` 中同时依赖三个学习模块。

## 代码迁移位置

Java 包名大体保持不变，主要调整所在模块，方便继续已有练习：

- [`HarnessCore`](miniharness-spring-ai/src/main/java/com/chinazhouwy/miniharness/HarnessCore.java)、`model/ModelCreator`、`tool/`、`advisor/`、`interceptor/` → `miniharness-spring-ai/src/main/java/`。
- [`ModelConfig`](miniharness-core/src/main/java/com/chinazhouwy/miniharness/model/ModelConfig.java) → `miniharness-core/src/main/java/`，读取过程不依赖 Spring。
- [`HarnessCoreDemoTest`](study-spring-ai/src/test/java/com/chinazhouwy/miniharness/HarnessCoreDemoTest.java) → `study-spring-ai/src/test/java/`。后续 Spring AI 练习继续放在这里，使用中文 `@DisplayName`。
- `agentscope/study/` 全部移入 [`study-agentscope`](study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study/)。
- 原先同时引用两个框架的扩展点测试，拆成 Spring AI 的 [`AgentHookComparisonTest`](study-spring-ai/src/test/java/com/chinazhouwy/miniharness/springai/study/AgentHookComparisonTest.java) 与 AgentScope 的 [`AgentScopeHookTest`](study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study/AgentScopeHookTest.java)，使用相同问题分别运行。
- Alibaba 的 [`AlibabaReactAgentTest`](study-spring-ai-alibaba/src/test/java/com/chinazhouwy/miniharness/alibaba/study/AlibabaReactAgentTest.java) 演示 Graph 工具循环与 `invoke()` 的消息状态，包含离线验证与真实模型入口。

原根目录 `src/` 已拆走。IDE 重新加载根 `pom.xml`，每个模块分别拥有 `src/main`、`src/test` 与自己的测试资源。

## 模型配置

默认读取根目录的 `config/openrouter.local.properties`；从仓库根目录或子模块目录启动都能找到。现有本地文件已迁移，真实内容被 Git 忽略。新环境参照 [`config/openrouter.example.properties`](config/openrouter.example.properties) 创建本地文件：

```properties
miniharness.api.key=填写自己的密钥
miniharness.base.url=填写兼容 OpenAI 的服务地址
miniharness.model=填写模型名称
```

也可以通过环境变量 `MINIHARNESS_CONFIG` 或 JVM 参数 `-Dminiharness.config=/绝对路径/model.properties` 指定文件；JVM 参数优先。显式指定的路径不存在时直接报错。旧 `openrouter.*` 键继续兼容。

配置在运行时读取，不复制到模块的 resources，避免随着模块 JAR 打包。模型请求日志会打印提示词和工具内容，使用适合本地观察的测试数据。

## 构建与运行

需要 JDK 21+、Maven。以下命令均从仓库根目录运行：

```bash
# 编译、打包所有模块，并运行默认离线测试
mvn verify

# 只运行 Spring AI 的离线工具循环与文件工具验证，自动构建上游模块
mvn -pl study-spring-ai -am -Dtest=ManualSpringAiLoopTest,AgentUtilsToolStudyTest test

# 验证 AgentScope 带参数工具的 Schema 注册，不需要旧 agentscope-study profile
mvn -pl study-agentscope -am -Dtest=ToolSchemaIsolationTest test

# 验证 Alibaba Graph 的模型/工具循环与消息状态，不调用外部模型
mvn -pl study-spring-ai-alibaba -am -Dtest=AlibabaReactAgentTest#scriptedToolLoopReturnsState test

# 手动搭建最小执行图，观察 initGraph、条件路由和状态追加
mvn -pl study-spring-ai-alibaba -am -Dtest=AlibabaReactAgentTest#initGraphDefinesToolLoop test
```

需要真实模型/外部服务的示例标记为 `@Tag("live")`；耗时与并发观察练习标记为 `@Tag("manual")`，默认均不执行。显式选定一个 demo，并清空排除标签：

```bash
# Spring AI：观察一趟任务结束后的执行状态
mvn -pl study-spring-ai -am -Dtest=HarnessCoreDemoTest#chatWithRunState -DexcludedGroups= test

# Spring AI：重建客户端与记忆组件，从本地文件数据库接续聊天
mvn -pl study-spring-ai -am -Dtest=HarnessCoreDemoTest#chatWithPersistentMemory -DexcludedGroups= test

# Spring AI：通过 stdio 连接 filesystem MCP server，由模型读取临时文件
# 需要 Node.js/npm；首次运行 npx 可能下载固定版本的服务包。
mvn -pl study-spring-ai -am -Dtest=McpStudyTest#chatWithMcpFileTool -DexcludedGroups= test

# AgentScope：真实模型调用文件工具
mvn -pl study-agentscope -am -Dtest=AgentScopeFileDemo -DexcludedGroups= test

# AgentScope：关闭旧实例后，从文件恢复同一用户、同一会话
mvn -pl study-agentscope -am -Dtest=StateTest#test02SessionRecovery -DexcludedGroups= test

# Alibaba：真实模型调用工具后读取 OverAllState
mvn -pl study-spring-ai-alibaba -am -Dtest=AlibabaReactAgentTest#realModelReturnsState -DexcludedGroups= test

# Alibaba：共享内存 Saver，重建 Agent 后按会话 ID 接续并检查工具消息
mvn -pl study-spring-ai-alibaba -am -Dtest=AlibabaReactAgentTest#chatWithSessionCheckpoint -DexcludedGroups= test

# Alibaba：同版本 MCP 文件服务，观察 Graph 状态中的工具请求、结果与最终回答
mvn -pl study-spring-ai-alibaba -am -Dtest=McpStudyTest#chatWithMcpFileTool -DexcludedGroups= test

# AgentScope：同版本 MCP 文件服务，观察 Toolkit 注册和完整消息中的工具请求／结果
mvn -pl study-agentscope -am -Dtest=McpStudyTest#chatWithMcpFileTool -DexcludedGroups= test

# MCP 失败代表例：不创建目标文件，观察 ERROR 工具结果和模型的后续解释
mvn -pl study-agentscope -am -Dtest=McpStudyTest#chatWithMissingMcpFile -DexcludedGroups= test

# 上下文压缩：同一长工具结果的全文／截断／框架摘要对照，摘要生成额外调用模型
mvn -pl study-agentscope -am -Dtest=ContextCompressionTest#compareFullTruncatedAndSummary -DexcludedGroups= test

# 长期记忆：提取到每日文件，整理 MEMORY.md，新会话读取与纠错、不同用户隔离
mvn -pl study-agentscope -am -Dtest=LongTermMemoryTest#rememberCorrectAndRecall -DexcludedGroups= test

# RAG：本地关键词向量检索、按资料版本过滤、真实模型回答与来源核对，以及无命中对照
mvn -pl study-spring-ai -am -Dtest=HarnessCoreDemoTest#chatWithRetrievedDocuments -DexcludedGroups= test

# Skills：先看到名称和描述，模型按需加载 SKILL.md，再执行其中要求的 Java 工具
mvn -pl study-agentscope -am -Dtest=SkillsStudyTest#loadSkillThenUseTool -DexcludedGroups= test

# HTTP 技能仓库：测试自启本机 HTTP 服务，无需远端服务
mvn -pl study-agentscope -am -Dtest=SkillsStudyTest#loadSkillFromHttp -DexcludedGroups= test

# 普通 Nacos 配置项方案：模拟 getConfig 返回值，不连接真实 Nacos
mvn -pl study-agentscope -am -Dtest=SkillsStudyTest#loadSkillFromNacosConfigStub -DexcludedGroups= test

# 权限与人工确认：固定模型提出工具请求，验证真实框架的允许、拒绝和批复恢复；不需要 API Key
mvn -pl study-agentscope -am -Dtest=PermissionApprovalStudyTest test

# Java/Reactor 基础练习按方法单独运行
mvn -pl study-agentscope -am -Dtest=ReactorStudyTest#creation -DexcludedGroups= test
```

IDE 可直接运行具体测试方法；Maven 的默认标签排除配置不会限制 IDE 直接运行。部分旧示例依赖 Redis、MCP 或人工输入，运行前查看该方法说明。结构化输出 demo 中也保留了当前模型不支持原生 JSON Schema 的失败演示，不建议一次运行所有真实模型示例。

打包应用装配入口（尚非完整任务助手）：

```bash
mvn -pl examples/task-assistant -am -DskipTests package
java -jar examples/task-assistant/target/task-assistant-0.1.0.jar
```

## 学习方向

会话与状态的同题对照使用“记录学习任务，再重建组件询问任务”的案例：

| 框架 / 示例 | 保存机制 | 会话定位 | 当前实验的边界 |
| --- | --- | --- | --- |
| Spring AI：`chatWithPersistentMemory` | `MessageWindowChatMemory` + JDBC 仓库 + H2 文件 | `ChatMemory.CONVERSATION_ID` | 保存聊天记忆；当前 JDBC 仓库过滤工具调用消息。 |
| Alibaba：`chatWithSessionCheckpoint` | Graph 检查点 + `MemorySaver` | `RunnableConfig.threadId` | 保存图状态，观察到工具结果；重建 Agent 时共享同一个内存 Saver。 |
| AgentScope：`StateTest#test02SessionRecovery` | `AgentState` + `JsonFileAgentStateStore` | `RuntimeContext.userId` + `sessionId` | 关闭旧 Agent，重新创建存储和 Agent 后从文件读回会话；测试使用临时目录。 |

这些实验验证调用完成后的状态接续，没有演示实际重启 JVM 或在任意工具执行位置恢复。

MCP 示例各自放在独立的 `McpStudyTest`，使用同版本 filesystem server，通过 stdio 发现工具，只暴露 `read_text_file`，读取临时文件中的随机校验码后关闭连接：

- [Spring AI 示例](study-spring-ai/src/test/java/com/chinazhouwy/miniharness/McpStudyTest.java)：由 ChatClient 的工具循环执行。
- [Alibaba 示例](study-spring-ai-alibaba/src/test/java/com/chinazhouwy/miniharness/alibaba/study/McpStudyTest.java)：复用 MCP → ToolCallback 适配层，由 ReactAgent 的 Graph 执行循环；打印状态中的工具请求、结果和最终回答，并核对文件校验码。
- [AgentScope 示例](study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study/McpStudyTest.java)：使用 McpClientBuilder 和 Toolkit.registration 注册单个远程工具，由 ReActAgent 执行循环；显式设置模型可见的名称前缀，结束后打印完整消息中的 ToolUseBlock／ToolResultBlock，核对校验码。

失败代表例使用 AgentScope 的 `chatWithMissingMcpFile`：复用正常示例的连接／工具注册／消息打印，只改为不创建文件，观察 ERROR 工具结果及模型的后续解释。它验证工具执行失败的反馈，不代表协议异常、断线、关闭异常或另两套框架的失败路径已通过。

主线保持：工具循环观察 → 三框架对照与隔离环境 → 模型/消息/工具/输出契约 → 扩展点与执行边界 → 运行时/并发/流式 → 会话/状态/MCP → 上下文/记忆/RAG/Skills → 权限/检查点/恢复 → 工作流/多 Agent/完成验证 → 综合实践。

2026-11-18 接续安排：三框架 MCP 正常接入和 AgentScope 工具失败代表例已通过真实模型验证。接下来进入阶段 7；后四个大阶段合并为以下 8 组核心实验，组数不等于只需 8 次对话，不降低三框架功能、源码原理和差异比较的目标。

| 顺序 | 原阶段 | 核心实验 |
| --- | --- | --- |
| 1 | 7 | 长工具结果处理、消息窗口与摘要压缩对照 |
| 2 | 7 | 长期记忆的跨会话保存、检索、纠错和隔离 |
| 3 | 7 | 小语料 RAG：切分、检索、过滤和来源核对 |
| 4 | 7 | Skills 发现、渐进加载及工具执行边界 |
| 5 | 8 | 工具权限、允许／拒绝／等待、人工确认及不可信工具输出 |
| 6 | 8 | 检查点、暂停恢复、实际进程重启、幂等及工作区隔离 |
| 7 | 9 | 顺序／并行工作流、路由与多 Agent 委派、失败归属和完成验证 |
| 8 | 10 | 三框架比较、少量案例评估和综合实践 |

第 1 组已新增 [ContextCompressionTest](study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study/ContextCompressionTest.java) 代表实验：手工构造工具历史，调用 AgentScope 的 `ConversationCompactor`，再用真实模型对照全文、截断和摘要。实测全文及本次摘要保留了工单号、验收码和操作限制，截断丢失后两项；摘要将 4 条历史缩为 2 条。它单独验证压缩组件，不代表自动触发、会话写回、长结果外置、另两套框架摘要机制或所有信息无损已验证。

第 2 组已新增 [LongTermMemoryTest](study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study/LongTermMemoryTest.java) 代表实验：显式调用 `MemoryFlushManager` 提取到每日账本、`MemoryConsolidator` 整理到 `MEMORY.md`，由新建 Harness 的新会话加载记忆。真实模型已验证原值读取、明确纠错后读取新值及不同用户隔离；每日账本仍保留旧历史。为保证观察顺序，本例关闭自动后台记忆任务并等待主动写入完成，不代表自动触发、删除／过期、另两框架记忆组合或实际进程重启均已验证。

第 3 组已在 [HarnessCoreDemoTest](study-spring-ai/src/test/java/com/chinazhouwy/miniharness/HarnessCoreDemoTest.java) 新增 `chatWithRetrievedDocuments`：TokenTextSplitter 处理短资料，SimpleVectorStore 存储本地二值关键词向量，QuestionAnswerAdvisor 按版本过滤、检索并增强提示词。真实模型实测命中 2026 退款规则后答出 3 个工作日、随机核验码和正确来源；2099 版本无命中时明确无法确定。应用从 RETRIEVED_DOCUMENTS 核对实际来源，来源标识也显式写入正文。此例不代表神经语义 Embedding、长文切分效果、混合检索／重排或另外两套框架的 RAG 已验证。

第 4 组已新增 [SkillsStudyTest](study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study/SkillsStudyTest.java)：ReActAgent 挂载只读 FileSystemSkillRepository，自动接入 DynamicSkillMiddleware 和加载工具。真实模型实测先看到技能目录但看不到正文随机标记，调用 `load_skill_through_path` 后正文才进入模型请求，再执行 `query_ticket` Java 工具，最终输出指定简报与标记。这里的渐进加载指正文进入模型上下文的时机，Java 端可能早已读取文件；Skill 本身不新增工具权限。本例未验证附属脚本、热更新或另两框架 Skills 机制。后续进入权限与人工确认专题。

同类中补充远程来源对照，仓库适配器位于 [RemoteSkillRepositories](study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study/RemoteSkillRepositories.java)：

- `loadSkillFromHttp`：通过真实 HTTP 请求本机临时服务。`GET /skills` 返回技能名称数组，`GET /skills/ticket-brief` 返回含 frontmatter 的 Markdown；目录和正文读取后适配为 AgentSkill，后续流程复用原示例。
- `loadSkillFromNacosConfigStub`：模拟普通配置中心的 `getConfig(dataId, group)`，示例映射为 `namespace=demo-team`、`group=AGENT_SKILLS`、`dataId=skills.ticket-brief.md`。此处并没有连接 Nacos。
- `connectRealNacosConfigExample`：实际使用 Nacos SDK 创建 ConfigService，读取相同配置并挂到 Agent；标有 `@Disabled`，默认不会运行。准备服务、namespace ID、配置项及认证后再启用，结束时释放客户端。

本地目录、HTTP、模拟配置读取三个真实模型流程已通过；真实 Nacos 方法仅编译验证。配置中心方案是本项目对 AgentSkillRepository 的适配，不是官方 NacosSkillRepository／Nacos AI Skill ZIP 服务；本例也没有配置监听、远端附属资源或生产缓存。

优先复用已有实验补对照，机制不同再增加独立示例。前面尚未收口的并发／取消、各框架契约与扩展点比较，以及 MCP Resources／Prompts、其余失败路径仍保留待补，不因进入阶段 7 而记为完成。

第 5 组权限与人工确认新增 [PermissionApprovalStudyTest](study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study/PermissionApprovalStudyTest.java)：两个离线测试覆盖 ALLOW、DENY、ASK 后批准、ASK 后拒绝。模型响应固定，权限引擎、事件、工具执行和会话恢复使用真实 AgentScope；断言依据是 Java 工具的实际执行次数。确认结果绑定原 ToolUseBlock，通过 Msg.METADATA_CONFIRM_RESULTS 回填，同一会话使用新 RuntimeContext 继续。测试中的批准／拒绝由代码模拟，没有人机界面，也未验证跨进程恢复、参数级文件权限、不可信工具输出或另两框架对应机制。

目前已有 Spring AI 工具循环、日志、会话参数、结构化输出、工具异常、预算与超时练习，也有 AgentScope 的分主题实验；模块拆分不代表所有专题都已完成。后续按专题分别在三个 `study-*` 模块运行对照，确有可复用契约时再提取到 core。

原 README 的长程任务与股票研究说明对应旧分支，当前源码没有这些后端实现，已移除失效的入口和文档链接。公众号待发布稿与每日总结继续放在仓库外的 `/Users/chinazhouwy/Documents/公众号文章`。

参考：[Maven 多模块构建](https://maven.apache.org/guides/mini/guide-multiple-modules.html)、[Spring AI](https://docs.spring.io/spring-ai/reference/)、[Spring AI Alibaba](https://www.java2ai.com/docs/quick-start/)、[AgentScope Java](https://github.com/agentscope-ai/agentscope-java)。

## MiniHarness v0.1：自进化 Agent Runtime & Studio（2026-11-18）

在本分支（`codex/miniharness-self-evolving-v01`）上完成了第一个自进化闭环原型，**学习模块保持不动**。详见 [docs/DESIGN-v0.1.md](docs/DESIGN-v0.1.md)（方案）与 [docs/TECH-REPORT-v0.1.md](docs/TECH-REPORT-v0.1.md)（复盘报告，含已实现/仍是概念的边界说明）。

```text
miniharness-runtime/        # 新增：框架无关 Harness 内核（状态机/事件/DAG编译执行/路由/进化引擎），只依赖 JDK+Jackson
studio/                     # 新增：Vue3+TS+Pinia+Vue Flow 前端，全部消费真实 API（无 Mock）
examples/task-assistant/    # 扩展：HarnessEngine 装配 + REST/SSE API，UI+API 单 jar 提供
miniharness-spring-ai/      # 扩展：SpringAiModelPort 适配器（依赖方向：适配层→内核）
```

可运行闭环：用户输入 → 规则路由匹配任务/Workflow → 执行 v1 → 事件与轨迹落账 → 失败经验产生 v2 候选（插入口径验证节点）→ 训练/留出双集对比评测 → 人工批准/拒绝 → 发布新版本 → 再次执行（行为真实不同）→ 回滚。金融数据全部为标注的合成数据，不接入真实行情与交易。

```bash
# 后端 + 前端单 jar（离线确定性模式；auto 模式且配置了模型时自动接真实模型）
mvn clean package -DskipTests -pl examples/task-assistant -am
MINIHARNESS_MODEL_MODE=offline java -jar examples/task-assistant/target/task-assistant-0.1.0.jar
# 浏览器打开 http://127.0.0.1:8080/

# 前端开发模式（/api 代理到 8080）
cd studio && npm install && npm run dev

# 全部离线测试（56 个，live/manual 默认排除；端到端闭环在 EndToEndClosedLoopTest）
mvn test
```

