# MiniHarness

MiniHarness 是一个从 Spring AI 学习 Demo 和控制台面试原型开始的 Java 实验项目。

它的目标不是立刻做出一套“大而全的 AI 面试平台”，而是沿着真实遇到的问题往前走：先看模型怎样回答、怎样理解上下文、怎样返回结构化结果、怎样调用工具；再观察这些能力放进面试场景后到底哪里不可靠，最后才决定是否需要状态机、评测规则、持久化或更通用的 Harness。

当前代码有两条并行但相关的线：

```text
Spring AI 学习 Demo
    ChatClient / Structured Output / Chat Memory / Tool Calling

控制台面试原型
    意图识别 -> 出题或提示 -> 回答评测 -> 追问 -> JSON 历史
```

它们都还是学习代码，不是已经完成的产品。

## 先从哪里读

如果刚打开这个仓库，推荐按下面顺序：

1. [SpringAiChatClientDemoTest](src/test/java/com/chinazhouwy/miniharness/SpringAiChatClientDemoTest.java)：最普通的一次模型调用、System Prompt、参数化 Prompt、流式输出。
2. [SpringAiStructuredOutputDemoTest](src/test/java/com/chinazhouwy/miniharness/SpringAiStructuredOutputDemoTest.java)：模型文字如何映射为 Java `record`。
3. [SpringAiChatMemoryDemoTest](src/test/java/com/chinazhouwy/miniharness/SpringAiChatMemoryDemoTest.java)：为什么 `memoryAdvisor` 和 `conversationId` 必须同时存在。
4. [SpringAiToolCallingDemoTest](src/test/java/com/chinazhouwy/miniharness/SpringAiToolCallingDemoTest.java)：工具“可用”和工具“必须被调用”是两回事；自动循环与手动循环分别意味着什么。
5. [MiniHarnessDemo](src/main/java/com/chinazhouwy/miniharness/MiniHarnessDemo.java)：把上述能力放进一个能输入、能追问的控制台原型后，问题是怎么自然出现的。

每个关键位置都补了中文注释。不要试图一次读完所有类，先运行一个 Demo，再顺着调用链看注释会舒服得多。

## 当前能力

| 能力 | 当前实现 | 目的 |
|---|---|---|
| 基础对话 | `ChatClient` | 熟悉 Prompt、System Message 和同步/流式调用 |
| 结构化输出 | `entity(Record.class)` | 观察模型如何返回 Java 可读取的对象 |
| 多轮上下文 | `MessageChatMemoryAdvisor` | 理解模型无状态、Memory 只是上下文窗口 |
| 工具调用 | 自动调用与手动循环各一例 | 理解模型只提出工具请求，Java 才真正执行工具 |
| 意图识别 | `IntentResult` + `InterviewIntent` | 将“用户说的话”转换为有限操作 |
| 面试原型 | `MiniHarnessDemo` | 体验出题、回答、评测、追问如何串在一起 |
| 文件持久化 | `data/history.json`、`data/attempts.json` | 区分对话上下文和“题目-回答-评测”快照 |

## 心路与思考过程

这个项目最初并不想只是做一个“面试助手”。普通聊天式模拟面试很容易出现一个问题：回答听起来差不多对，模型就说“回答得很好”；但真实面试官通常会继续问边界、实现原理和应用场景。真正想验证的是“似乎知道”和“真正掌握”之间的差别。

一开始很容易直接跳到大架构：多 Agent、RAG、向量库、状态机、长期记忆、评测平台……但那样的问题是，系统先长出来了，真实问题却还没出现。于是方向收回到更小的实验：先熟悉 Spring AI 的能力，再把它们接到一个控制台里，观察模型到底在哪儿帮上忙、又在哪儿不可靠。

当前控制台原型已经暴露出一些值得继续研究的地方：

- 意图识别、答案评测、追问生成是三次独立模型调用，它们的结论可能不一致。
- `AnswerEvaluation` 只有一个分数和一个遗漏点，无法解释“为什么是这个分数”。
- 目前把完整历史直接发给模型，历史变长后一定需要考虑上下文边界。
- Prompt 可以要求模型调用工具，但 `.tools(...)` 只是提供工具，不保证模型一定调用；若业务要求必须查询，Java 必须自行检查和约束。
- JSON 文件能帮助看清数据流，但不解决并发、恢复、版本和重复写入问题。

这些不是现在就必须解决的“待办清单”，而是后续每一步应该从哪里长出来的依据。MiniHarness 想保留这种过程：先写一个小实验，记录真实观察，再决定下一步，而不是假装一开始就知道最终架构。

## 项目结构

```text
src/main/java/com/chinazhouwy/miniharness/
├── MiniHarnessDemo.java          当前可交互的控制台原型
├── InterviewIntent.java          模型可返回的用户意图
├── IntentResult.java             意图识别的结构化结果
├── AnswerEvaluation.java         回答评测的最小结构
├── QuestionAttempt.java          题目、回答、评测快照
├── StoredMessage.java            用于 JSON 保存的简化消息
└── Interview*.java / Question*.java
                                 正在整理的领域模型草图

src/test/java/com/chinazhouwy/miniharness/
├── SpringAiChatClientDemoTest.java
├── SpringAiStructuredOutputDemoTest.java
├── SpringAiChatMemoryDemoTest.java
└── SpringAiToolCallingDemoTest.java

data/
├── history.json                  控制台对话历史
└── attempts.json                 回答评测快照

docs/experiments/
└── 000-spring-ai-demo.md         第一组 Spring AI 学习 Demo 说明
```

## 环境与配置

- Java 21
- Maven 3.9+
- Spring Boot 4.1.0
- Spring AI 2.0.0
- 一个支持 OpenAI-compatible API 的模型服务；当前示例是 DeepSeek

当前 `MiniHarnessDemo` 和 Spring AI 学习测试都从本机环境变量读取 Key：

```bash
export DEEPSEEK_API_KEY='你的 API Key'
```

模型地址和名称目前直接写在两处学习代码中，便于入门时一眼看清：

- [MiniHarnessDemo.java](src/main/java/com/chinazhouwy/miniharness/MiniHarnessDemo.java)
- [SpringAiLlmDemoSupport.java](src/test/java/com/chinazhouwy/miniharness/SpringAiLlmDemoSupport.java)

这不是生产配置方案，只是当前 Demo 的有意取舍。真实 Key 不要写进源码或提交到 Git。

## 运行 Spring AI 学习 Demo

先运行全部学习测试：

```bash
mvn -Dtest='SpringAi*DemoTest' test
```

工具调用不一定被每个模型支持。确认模型支持 tool/function calling 后，将
`SpringAiLlmDemoSupport.RUN_TOOL_DEMOS` 改为 `true`，再单独运行：

```bash
mvn -Dtest=SpringAiToolCallingDemoTest test
```

学习重点不是背 API，而是理解下面这几个边界：

```text
ChatModel       模型调用的底层抽象
ChatClient      建 Prompt 的链式 API
Chat Memory     为模型补回有限的历史上下文，不是业务历史数据库
Tool Calling    模型请求调用；Java 决定是否执行、如何执行
Structured Output
                模型输出映射成对象，但仍需要业务代码验证
```

更细的说明见 [实验 000](docs/experiments/000-spring-ai-demo.md)，Spring AI 官方文档见：
[Chat Client](https://docs.spring.io/spring-ai/reference/api/chatclient.html)、
[Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)、
[Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)、
[Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)。

## 运行控制台原型

当前入口是 `MiniHarnessDemo.main()`。可以在 IDE 中直接运行该类，或先编译后运行：

```bash
mvn compile
java -cp target/classes com.chinazhouwy.miniharness.MiniHarnessDemo
```

启动后可以尝试输入：

```text
下一题
给我一个提示
讲解一下当前题
我认为 ConcurrentHashMap 就是在 HashMap 外面加一把锁
exit
```

当前运行前注意两件事：

1. `InterviewQuestion` 引用了尚未定义的 `QuestionStatus`，因此仓库此刻会编译失败。这是正在整理的领域草图，尚未接入控制台主流程；补齐或移除这部分草图后，才可以运行上述命令。
2. `data/history.json` 仍保留过往直接序列化 Spring AI `Message` 的旧格式；当前代码已改为读取 `StoredMessage(role, content)`。第一次测试新的保存/恢复逻辑前，建议备份或删除旧 `data/history.json`，让程序以新格式重新生成。

## 目前没有做什么

当前项目没有数据库、Web API、前端、多 Agent、RAG、向量库、MCP、完整状态机，也没有所谓“通用 Agent Runtime”。这些不是遗漏，而是刻意不提前做。只有当控制台实验明确暴露出重复逻辑、上下文过长、评测无法比较或恢复失败等具体问题时，再决定是否抽取相应能力。

## 下一步如何决定

下一步不预设成大工程。一次实际运行后，优先记录三个问题：

1. 模型的评分和追问是否真的对应回答的缺口？
2. 三个独立调用是否出现互相矛盾的结论？
3. JSON 历史恢复后，模型还能不能正确理解当前题和当前阶段？

如果问题集中在评测不稳定，下一步应该是给一道题补人工 Rubric 和几个固定回答样本；如果问题集中在流程混乱，才考虑把 `InterviewState` 和 `InterviewSession` 真正接进控制台。让问题决定抽象，而不是反过来。

## 当前已知限制

- 模型输出具有不确定性，分数和 `correct` 不能视为客观结论。
- 结构化输出还没有分数范围、空字段或引用真实性校验。
- 每个模型调用都重新发送手动维护的历史；没有上下文长度控制。
- `FOLLOW_UP`、`CHAT` 等意图已预留，但控制台尚未实现对应分支。
- 当前每个已完成的控制台分支会立即写入本地 JSON；但文件写入仍没有事务、并发控制或崩溃恢复保障。
- `QuestionStatus` 缺失，当前 `mvn test` 会在主代码编译阶段失败。

这些限制会保留在 README 中，直到它们被真实代码和测试解决，而不是仅仅从文档里消失。

## 后续规划

按真实问题和优先级整理的后续清单见 [后续规划](docs/next-steps.md)。它把当前编译阻塞、会话恢复、评测可信度和未来扩展分开，避免把所有想法一次性塞进项目。
