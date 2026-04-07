#!/bin/bash
# 初始化数据库和 Kafka Topic

echo "Initializing databases..."

# 创建 Kafka Topics
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic market-data --partitions 6 --replication-factor 1 || true
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic trade-orders --partitions 6 --replication-factor 1 || true
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic agent-technical-signal --partitions 3 --replication-factor 1 || true
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic agent-sentiment-signal --partitions 3 --replication-factor 1 || true
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic agent-fundamental-signal --partitions 3 --replication-factor 1 || true
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic agent-risk-signal --partitions 3 --replication-factor 1 || true
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic trade-decision --partitions 3 --replication-factor 1 || true

echo "Kafka topics created!"

# 初始化 PostgreSQL
echo "Initializing PostgreSQL..."
# TODO: 执行 SQL 脚本

echo "Initialization complete!"
