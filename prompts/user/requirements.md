# User Prompts and Requirements

## Initial Request
"Create or update `AGENTS.md` for this repository.

The goal is a compact instruction file that helps future OpenCode sessions avoid mistakes and ramp up quickly. Every line should answer: 'Would an agent likely miss this without help?' If not, leave it out."

## Framework Creation Request
"基于这个目标，你在当前的目录下生成项目的初试框架吧，搭建一下,只需要搭建框架就行，代码我自己后续写"

## Directory Structure Requirements
From README.md planning:
```
services/     → Java 微服务（quote, strategy, order, account, risk, backtest）
agents/       → Python Agent（orchestrator, technical, sentiment, fundamental, risk_manager）
web-ui/       → Vue 3 前端
harness/      → Harness Engineering 工具（mcp-servers, validators, feedback）
simulation/   → 二期：情景推演引擎（Python + Neo4j）
scripts/      → 运维脚本
data/         → 数据样例
docs/         → 架构、API、面试清单、路线图
```

## Technology Stack Requirements
- **Java 服务启动**: `mvn spring-boot:run`，通过 `-javaagent` 挂载 SkyWalking 探针
- **Agent 启动**: `python main.py --config config.yaml`，独立 Python 进程，通过 Kafka 通信  
- **前端启动**: `npm install && npm run dev`，访问 `localhost:5173`
- **基础设施**: `docker-compose up -d` 启动所有服务
- **MCP 协议**: 后端能力封装为 MCP Server 供 Agent 调用
- **Harness Engineering**: AGENTS.md + 自动化质检 + 结构化反馈闭环

## Development Order
1. Docker Compose 基础设施
2. Netty + Disruptor 行情管道  
3. 双均线回测引擎（Python）
4. Spring Cloud 微服务拆分
5. Kafka / RocketMQ 集成
6. AgentScope 多智能体 + Redis 向量记忆
7. Harness 质检流水线 + K8s 部署
