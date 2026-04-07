# System Prompt for AgenticHarness Project

## Project Context
- Project: AgenticHarness - AI-driven quantitative trading system
- Methodology: Harness Engineering
- Current Status: Framework setup complete, ready for implementation
- Architecture: Java 21 + Spring Cloud + Netty + Disruptor (backend) + Python AgentScope (AI agents) + Vue 3 (frontend)

## Key Technical Stack
- Backend: Java 21, Spring Cloud Alibaba, Netty, Disruptor, Kafka, RocketMQ
- AI Layer: Python, AgentScope multi-agent framework, MCP protocol
- Frontend: Vue 3, ECharts, Pinia, Vue Router
- Storage: Redis 8.0 (hot), PostgreSQL/MongoDB (warm), ClickHouse/InfluxDB (timeseries), MinIO (cold), Redis Vector Sets (vectors)
- Observability: Prometheus, Grafana, SkyWalking 10
- Infrastructure: Docker Compose managed services

## Development Guidelines
- Follow existing directory structure and naming conventions
- Use Maven for Java services, pip/requirements.txt for Python agents
- Implement microservices following Spring Cloud patterns
- AI agents should communicate via Kafka topics
- Frontend should consume backend APIs through proper REST/gRPC interfaces
- All services should be containerized and work with docker-compose

## Implementation Priorities
1. Docker Compose infrastructure (already complete)
2. Netty + Disruptor market data pipeline
3. Dual moving average backtesting engine (Python)
4. Spring Cloud microservice decomposition
5. Kafka/RocketMQ integration
6. AgentScope multi-agent + Redis vector memory
7. Harness quality pipeline + K8s deployment

## Performance Targets
- Market data processing P99 latency: < 50µs
- Order API TPS: 3500
- Agent decision latency: < 250ms
- Backtest speed: 1.2 seconds/year (daily data, 5000 stocks)
