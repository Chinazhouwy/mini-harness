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

MCP 示例统一放在独立的 [`McpStudyTest`](study-spring-ai/src/test/java/com/chinazhouwy/miniharness/McpStudyTest.java)。已先验证 Spring AI 的 `chatWithMcpFileTool`：通过 stdio 启动固定版本 filesystem server，发现工具，只暴露 `read_text_file`，模型读取临时文件并返回随机校验码，最后关闭客户端及子进程。Alibaba、AgentScope 的同服务对照与工具失败实验待继续。

主线保持：工具循环观察 → 三框架对照与隔离环境 → 模型/消息/工具/输出契约 → 扩展点与执行边界 → 运行时/并发/流式 → 会话/状态/MCP → 上下文/记忆/RAG/Skills → 权限/检查点/恢复 → 工作流/多 Agent/完成验证 → 综合实践。

目前已有 Spring AI 工具循环、日志、会话参数、结构化输出、工具异常、预算与超时练习，也有 AgentScope 的分主题实验；模块拆分不代表所有专题都已完成。后续按专题分别在三个 `study-*` 模块运行对照，确有可复用契约时再提取到 core。

原 README 的长程任务与股票研究说明对应旧分支，当前源码没有这些后端实现，已移除失效的入口和文档链接。公众号待发布稿与每日总结继续放在仓库外的 `/Users/chinazhouwy/Documents/公众号文章`。

参考：[Maven 多模块构建](https://maven.apache.org/guides/mini/guide-multiple-modules.html)、[Spring AI](https://docs.spring.io/spring-ai/reference/)、[Spring AI Alibaba](https://www.java2ai.com/docs/quick-start/)、[AgentScope Java](https://github.com/agentscope-ai/agentscope-java)。
