#!/bin/bash
# 启动基础设施服务

echo "Starting AgenticHarness infrastructure..."

# 启动 Docker Compose
docker-compose up -d

# 等待服务就绪
echo "Waiting for services to be ready..."
sleep 10

# 检查服务状态
docker-compose ps

echo "Infrastructure started!"
echo "Redis: localhost:6379"
echo "Kafka: localhost:9092"
echo "PostgreSQL: localhost:5432"
echo "MongoDB: localhost:27017"
echo "ClickHouse: localhost:8123"
echo "MinIO Console: http://localhost:9001"
echo "Neo4j: http://localhost:7474"
