# AGENTS.md – AgenticHarness

## 项目状态

- **当前状态**：规划阶段，仅含 README.md 设计文档，**无源码、无配置、无 CI**
- 所有 TODO 项均未勾选，代码实现尚未开始

## 技术栈与架构（规划中）

| 层 | 技术 |
|---|---|
| 后端 | Java 21 + Spring Cloud Alibaba + Netty + Disruptor |
| AI Agent | Python + AgentScope 多智能体 + MCP 协议 |
| 前端 | Vue 3 + ECharts |
| 消息 | Kafka（A2A 通信）+ RocketMQ（订单） |
| 存储 | Redis 8.0（热）→ PostgreSQL/MongoDB（温）→ ClickHouse/InfluxDB（时序）→ MinIO（冷）→ Redis Vector Sets（向量） |
| 可观测 | Prometheus + Grafana + SkyWalking 10 |

## 规划目录结构

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

## 关键约定

- **Java 服务启动**：`mvn spring-boot:run`，Java 服务通过 `-javaagent` 挂载 SkyWalking 探针
- **Agent 启动**：`python main.py --config config.yaml`，每个 Agent 是独立 Python 进程，通过 Kafka 通信
- **前端启动**：`npm install && npm run dev`，访问 `localhost:5173`
- **基础设施**：`docker-compose up -d` 启动 Redis, Kafka, PostgreSQL, ClickHouse, MinIO
- **MCP 协议**：后端能力封装为 MCP Server 供 Agent 调用（如 `quote-mcp-server`）
- **Harness Engineering**：AGENTS.md + 自动化质检 + 结构化反馈闭环，回测夏普 < 1.0 时自动生成反馈

## 开发顺序建议

1. Docker Compose 基础设施
2. Netty + Disruptor 行情管道
3. 双均线回测引擎（Python）
4. Spring Cloud 微服务拆分
5. Kafka / RocketMQ 集成
6. AgentScope 多智能体 + Redis 向量记忆
7. Harness 质检流水线 + K8s 部署
