**三框架同题：工具循环、消息状态与结果核对**

本页对应学习安排第 8 组的第一个小型综合实验。记录的是当前依赖版本和本次真实模型运行结果；未覆盖能力继续保留在 README 的待补项中。

**运行入口与版本**

| 框架 | 当前版本 | 运行方法 |
| --- | --- | --- |
| Spring AI | 2.0.2 | [HarnessCoreDemoTest#compareTicketTask](../study/study-spring-ai/src/test/java/com/chinazhouwy/miniharness/HarnessCoreDemoTest.java) |
| Spring AI Alibaba | 1.1.2.2，使用 Spring AI 1.1.2 | [FrameworkComparisonStudyTest#compareTicketTask](../study/study-spring-ai-alibaba/src/test/java/com/chinazhouwy/miniharness/alibaba/study/FrameworkComparisonStudyTest.java) |
| AgentScope | 2.1.0-SNAPSHOT | [FrameworkComparisonStudyTest#compareTicketTask](../study/study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study/FrameworkComparisonStudyTest.java) |

三个模块分别解析依赖。没有把两个 Spring AI 版本放进同一个模块，也没有为这次学习创建通用生产接口。小案例逻辑分别保留在模块内，只有框架适配方式不同。

可以在 IDE 单独运行对应方法，也可以一次运行三份同题 demo：

```bash
mvn -pl study/study-spring-ai,study/study-spring-ai-alibaba,study/study-agentscope -am \
  '-Dtest=HarnessCoreDemoTest#compareTicketTask,FrameworkComparisonStudyTest#compareTicketTask' \
  -DexcludedGroups= test
```

**相同的问题、工具与核对方式**

用户问题统一为：查询 DEMO-001 的状态和下一步建议，并附核验码。工单和规则全部是本地虚构资料；模型请求使用根目录本地配置，核验码在每个案例运行时随机生成，不放进提示词。

| 步骤 | 模型或应用做什么 | 实际数据来自哪里 |
| --- | --- | --- |
| 1 | 模型请求 query_ticket(ticketId) | Java 工具返回状态和随机核验码 |
| 2 | 模型请求 query_handling_rule(status) | 必须使用第一步查到的实际状态，Java 工具返回建议 |
| 3 | 模型生成最终回答 | 保留工单号、状态、建议和核验码 |
| 4 | Java 应用打印核对结果 | 实际调用计数、规则入参，以及最终回答包含的核验码和建议 |

两个案例分别是“处理中 → 继续跟进”和“已完成 → 可以结案”。它们是查询与建议，不执行真实结案操作。规则工具在状态参数不匹配时会抛出异常；先查工单再查规则的顺序由提示词要求，并通过实际消息链核对，本例没有额外的顺序门禁。

所有 demo 只打印观察结果，不添加业务断言。`核验码保留`、`建议匹配` 使用原文包含判断，是这个小案例的检查方式，不能推广为通用语义评估器。

**2026-11-18 实测结果**

三个 JUnit 方法均成功运行，每个方法内部执行两个案例。人工核对六条“应用核对”日志及其前面的消息链：

| 框架 | 案例状态 | 助手消息数 | 查工单 / 查规则 | 规则参数匹配 | 核验码保留 | 建议匹配 |
| --- | --- | --- | --- | --- | --- | --- |
| Spring AI | 处理中 | 3 | 1 / 1 | true | true | true |
| Spring AI | 已完成 | 3 | 1 / 1 | true | true | true |
| Alibaba | 处理中 | 3 | 1 / 1 | true | true | true |
| Alibaba | 已完成 | 3 | 1 / 1 | true | true | true |
| AgentScope | 处理中 | 3 | 1 / 1 | true | true | true |
| AgentScope | 已完成 | 3 | 1 / 1 | true | true | true |

助手消息数包括两轮工具请求与最后一轮回答，按最终消息记录统计，不包含 SDK 的网络重试次数。实际模型可以改变调用路径，本次的三轮不是框架保证的固定轮数。

这些结果验证这个受控小任务能完成，不构成质量或性能排名。AgentScope 本例使用流式模型接收并聚合结果；另外两个本例走同步调用，未统一网络、温度、采样和计费统计条件，也没有比较 token 成本。

**API 与运行机制的对应关系**

| 关注点 | Spring AI 本例 | Alibaba 本例 | AgentScope 本例 |
| --- | --- | --- | --- |
| 对外执行入口 | ChatClient.prompt().call().content() | ReactAgent.invoke() | ReActAgent.call(...).block(...) |
| 工具注册 | Spring AI @Tool / @ToolParam，交给 .tools(tools) | 同一套 Spring AI 注解，经 ToolCallbacks.from 转换 | AgentScope @Tool / @ToolParam，经 Toolkit 注册 |
| 循环由谁组织 | 显式配置的 ToolCallingAdvisor | ReactAgent 的 Graph 模型节点与工具节点 | ReActAgent 内部 reasoning / acting 迭代 |
| 工具结果怎样继续参与推理 | Advisor 内部历史进入下一轮 Prompt | 工具节点结果并入 messages 图状态，再进入模型节点 | ToolResultBlock 进入会话上下文，再进入 reasoning |
| 完整消息从哪里读 | 本例 CallAdvisor 保存每轮快照与响应 | invoke 返回 OverAllState，读取 messages | 读取指定 userId / sessionId 的 AgentState.getContext() |
| 本例怎样观察 | CallAdvisor 放在 ToolCallingAdvisor 内层 | 执行结束后读取图状态 | 执行结束后读取会话状态 |

Spring AI 2.0.2 本例的工具循环在 Advisor 链中，不能套用旧版“ChatModel 内部自动执行工具”的说法。[ToolCallingAdvisor 官方文档](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html)

Alibaba 的 ReactAgent 在 Graph 运行时上组织模型节点、工具节点和条件路由；call 返回最终消息，invoke 可读取完整状态。本例使用 invoke。[Alibaba Agents 官方文档](https://www.java2ai.com/docs/frameworks/agent-framework/tutorials/agents/)

AgentScope 的 ReActAgent 提供 Agent 调用入口，并支持会话、工具和中间件；本例使用核心 ReActAgent，没有启动工作区、长期记忆或子 Agent。[AgentScope Agent 官方文档](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/building-blocks/agent.md)

**对着源码阅读的入口**

以下符号已按当前本地依赖源码核对。先沿消息链找循环，随后再进入预算、异常和记忆扩展，不必一次展开所有分支。

| 框架 | 先读的 API / 类 | 要回答的问题 |
| --- | --- | --- |
| Spring AI | ToolCallingAdvisor、ToolCallingManager；本例 comparison-recorder | 为什么一个 client.call 会产生多轮模型回复？下一轮 Prompt 从哪里来？ |
| Alibaba | ReactAgent.initGraph、AgentLlmNode、AgentToolNode、条件边 | 什么时候去工具节点，什么时候回模型节点，什么时候结束？ |
| AgentScope | ReActAgent.executeIteration、reasoning、acting、executeToolCalls | 工具结果在哪里写回上下文，下一轮迭代如何触发？ |

**本轮边界与后续收口**

本轮把工具定义、参数依赖、框架循环、消息记录和基于事实的结果核对连成一个小任务。没有把长期记忆、RAG、Skills、权限审批和多 Agent 一次塞进同一个例子；这些继续引用各专题已有实验。

同题失败与输入不完整的三框架对照、统一成本与延迟测量、跨进程恢复、并发取消、三框架完整协作对照仍待补。当前仓库另有 miniharness-runtime 与 Studio 原型，那条实现线的进度不自动计为学习 demo 已覆盖。
