# MiniHarness

MiniHarness 是一个从单题评测实验开始，逐步探索评测、追问、能力证据和 Agent Runtime 的 Java 项目。

当前阶段不预设最终架构。每次只实现一个最小实验，记录实际问题，再决定下一步。

## 当前状态

- ✅ 实验 001：单题回答评测（已完成）

## 环境要求

- Java 21+
- Maven 3.9+

## 快速开始

### 1. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填入真实的 API Key、Base URL 和模型名称
```

加载环境变量：

```bash
export AI_API_KEY="your-api-key"
export AI_BASE_URL="https://your-provider-api-endpoint"
export AI_MODEL="your-model-name"
```

### 2. 构建项目

```bash
mvn compile
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

应用启动后会自动退出（当前没有 Web 端点或持续运行的任务）。

### 4. 运行测试

**普通测试**（不需要模型配置）：

```bash
mvn test
```

此命令运行 `MiniHarnessApplicationTests`，验证 Spring 上下文可以正常启动。

**LLM 集成测试**（需要模型配置）：

```bash
# 先设置环境变量，或使用 dotenv 工具
mvn test -Dgroups="llm"
```

如果没有设置 `AI_API_KEY`，LLM 测试会自动跳过，不会导致构建失败。

### 5. 实验输出

单题评测的实验输出保存在：

```
target/experiments/001-single-answer-evaluation-output.md
```

不要将生成结果写回 `src` 目录。

## 模型配置说明

### OpenAI 官方

```bash
export AI_API_KEY="sk-..."
export AI_BASE_URL="https://api.openai.com"
export AI_MODEL="gpt-4o"
```

### 其他 OpenAI 兼容 Provider

如果使用兼容 OpenAI API 的第三方 Provider，只需调整 `AI_BASE_URL`：

```bash
export AI_BASE_URL="https://your-provider-api-endpoint/v1"
```

注意：Spring AI 的 OpenAI starter 默认会自动拼接 `/v1/chat/completions` 路径。如果你的 Provider 的 base URL 已经包含完整路径或路径格式不同，可能需要在此基础上调整。请参考你的 Provider 文档。

## 当前明确没有实现的功能

- InterviewSession / 面试状态机
- 多题面试
- 数据库（PostgreSQL、Flyway）
- 前端页面
- REST API
- Agent / ReAct Loop
- MiniHarness Runtime
- AgentState / AgentStateStore
- Hook / Middleware
- Tool Calling / MCP
- RAG / 向量数据库
- AgentScope
- LangChain4j
- 结构化 EvaluationResult / JSON Schema
- EvalRunner
- 多 Maven 模块
- 用户登录
- 多 Provider 路由
- 重试框架
- 生产级异常体系

以上内容将在后续实验中根据实际需要逐步引入。

## 技术栈

- Java 21
- Spring Boot 4.1.0
- Spring AI 2.0.0
- JUnit 5
- Maven

## 实验记录

- [实验 001：单题回答评测](docs/experiments/001-single-answer-evaluation.md)

## License

Private project.
