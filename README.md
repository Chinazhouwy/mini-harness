# MiniHarness

MiniHarness 是一个从 Spring AI 学习 Demo 开始，逐步探索面试评测、追问、能力证据和 Agent Runtime 的 Java 项目。

当前阶段只做 **实验 000：Spring AI 基础能力速览**，先熟悉模型调用手感，不急着设计完整 Harness。

## 环境要求

- Java 21+
- Maven 3.9+

## 快速开始

普通测试不需要真实模型：

```bash
mvn test
```

Spring AI 学习 Demo 是手工配置的。先打开：

```text
src/test/java/com/chinazhouwy/miniharness/SpringAiLlmDemoSupport.java
```

修改这几行：

```java
protected static final String BASE_URL = "https://api.deepseek.com";
protected static final String MODEL = "deepseek-v4-flash";
protected static final String API_KEY = "replace-with-your-api-key";
```

然后运行：

```bash
mvn -Dtest='SpringAi*DemoTest' test
```

工具调用依赖具体模型能力。想试工具 Demo 时，把 `RUN_TOOL_DEMOS` 改成 `true` 后运行 `SpringAiToolCallingDemoTest`。

## 示例清单

| 测试类 | 示例 | 核心 API |
|---|------|----------|
| `SpringAiChatClientDemoTest` | 基础调用、System Prompt、参数化 Prompt、Token 用量、流式输出 | `ChatClient` |
| `SpringAiStructuredOutputDemoTest` | 结构化输出 | `call().entity(Record.class)` |
| `SpringAiChatMemoryDemoTest` | 多轮上下文 | `MessageChatMemoryAdvisor` |
| `SpringAiToolCallingDemoTest` | 自动工具调用、手动工具循环 | `ToolCallingAdvisor` / `ToolCallingManager` |

详细说明见 [实验 000](docs/experiments/000-spring-ai-demo.md)。

## 核心概念

```
ChatClient.Builder  ──注入──▶  构建 ChatClient
                                   │
    prompt()                       │
    ├── .system("...")  设置系统提示
    ├── .user("...")    设置用户消息
    │     ├── .text() + .param()  参数化模板
    │     └── ...
    ├── .advisors(...)  添加 Advisor（如多轮对话记忆）
    │
    ├── .call()         同步调用  ──▶  .content()       String
    │                              ──▶  .chatResponse()  ChatResponse（含 Token 用量）
    │                              ──▶  .entity(Type)    结构化对象
    │
    └── .stream()       流式调用  ──▶  .content()       Flux<String>
                                   ──▶  .chatResponse()  Flux<ChatResponse>
```

## 技术栈

- Java 21
- Spring Boot 4.1.0
- Spring AI 2.0.0
- Maven
