# 实验 000：Spring AI 基础能力速览

## 背景

MiniHarness 现在还不急着设计完整面试流程。第一步先熟悉 Spring AI 的常用能力，知道模型调用、Prompt、流式输出、结构化输出、Chat Memory 和 Tool Calling 分别怎么写。

这些 Demo 不是业务代码，只是学习笔记。后续真正做单题评测时，再从这些能力里挑需要的部分。

## Demo 清单

| 测试类 | 学习点 |
|---|---|
| `SpringAiChatClientDemoTest` | 基础调用、System Prompt、参数化 Prompt、Token Usage、Streaming |
| `SpringAiStructuredOutputDemoTest` | `entity(Class)` 映射 Java Record |
| `SpringAiChatMemoryDemoTest` | `MessageChatMemoryAdvisor` 和 conversation id |
| `SpringAiToolCallingDemoTest` | `ChatClient` 自动工具调用、`ChatModel` 手动工具循环 |

## 运行方式

普通测试不需要真实模型：

```bash
mvn test
```

运行 Spring AI 学习 Demo 前，先打开：

```text
src/test/java/com/chinazhouwy/miniharness/SpringAiLlmDemoSupport.java
```

修改 Demo 手工配置区：

```java
protected static final String BASE_URL = "https://api.deepseek.com";
protected static final String MODEL = "deepseek-v4-flash";
protected static final String API_KEY = "replace-with-your-api-key";
```

然后运行：

```bash
mvn -Dtest='SpringAi*DemoTest' test
```

工具调用对模型能力有要求。想试工具 Demo 时，把 `RUN_TOOL_DEMOS` 改成 `true` 后运行 `SpringAiToolCallingDemoTest`。

## 观察重点

- `ChatClient` 很适合学习和常规业务调用。
- `ChatResponse` 比 `content()` 多保留 token usage 等元数据。
- 结构化输出很方便，但仍需要业务侧校验。
- Chat Memory 是“发给模型的上下文窗口”，不是完整审计历史。
- Tool Calling 的安全边界在应用侧，模型只提出调用请求，真正执行工具的是 Java 代码。
- MiniHarness 以后更关心手动工具循环，因为它可以加入权限、状态保存、事件流和最大循环次数。

## 官方文档

- Chat Client API: https://docs.spring.io/spring-ai/reference/api/chatclient.html
- Structured Output Converter: https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html
- Chat Memory: https://docs.spring.io/spring-ai/reference/api/chat-memory.html
- Tool Calling: https://docs.spring.io/spring-ai/reference/api/tools.html
