#!/bin/bash
# 停止基础设施服务

echo "Stopping AgenticHarness infrastructure..."

docker-compose down

echo "Infrastructure stopped!"
