# MiniHarness

Spring AI 2.0 快速入门项目，涵盖常用 API 示例。

## 环境要求

- Java 21+
- Maven 3.9+

## 快速开始

```bash
cp .env.example .env
# 编辑 .env，填入真实的 API Key、Base URL 和模型名称
export $(cat .env | xargs)
mvn spring-boot:run
```

## 示例清单

`ChatDemo.java` 包含 7 个示例，运行时按顺序执行：

| # | 示例 | 核心 API |
|---|------|----------|
| 1 | **基础调用** | `prompt().user().call().content()` |
| 2 | **覆盖 System Prompt** | `prompt().system().user().call().content()` |
| 3 | **参数化模板** | `user(u -> u.text("{key}").param("key", val))` |
| 4 | **Token 用量** | `call().chatResponse().getMetadata().getUsage()` |
| 5 | **流式输出** | `stream().content()` → `Flux<String>` |
| 6 | **结构化输出** | `call().entity(Record.class)` → 直接映射 Java Record |
| 7 | **多轮对话** | `MessageChatMemoryAdvisor` + `advisors()` 自动管理上下文 |

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
