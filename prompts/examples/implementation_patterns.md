# Implementation Examples and Patterns

## Java Microservice Structure
```
services/{service-name}/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/agentic/{service}/
    │   │   ├── Application.java
    │   │   ├── config/
    │   │   ├── controller/
    │   │   ├── service/
    │   │   └── model/
    │   └── resources/
    │       ├── application.yml
    │       └── bootstrap.yml
    └── test/
        └── java/com/agentic/{service}/
```

## Python Agent Structure
```
agents/{agent-name}/
├── main.py
├── config.yaml  
└── (optional) requirements.txt
```

## Vue 3 Frontend Structure
```
web-ui/
├── package.json
├── vite.config.ts
├── .env.development
└── src/
    ├── main.ts
    ├── App.vue
    ├── router/
    ├── stores/
    ├── components/
    ├── views/
    ├── api/
    └── utils/
```

## Docker Compose Service Naming
- redis: harness-redis
- kafka: harness-kafka  
- postgres: harness-postgres
- mongodb: harness-mongodb
- clickhouse: harness-clickhouse
- minio: harness-minio
- neo4j: harness-neo4j

## Kafka Topics (to be created)
- market-data
- trade-orders  
- agent-technical-signal
- agent-sentiment-signal
- agent-fundamental-signal
- agent-risk-signal
- trade-decision

## Key Configuration Files Locations
- Prometheus: config/prometheus/prometheus.yml
- RocketMQ: config/rocketmq/broker.conf
- MCP Server: harness/mcp-servers/config.yaml
- Web UI Env: web-ui/.env.development

## Script Usage
- Start infra: ./scripts/start-infra.sh
- Stop infra: ./scripts/stop-infra.sh  
- Init data: ./scripts/init-data.sh
