# ClickHouse 学习指南

## 什么是 ClickHouse？

ClickHouse 是一个**高性能的列式数据库管理系统 (Column-Oriented DBMS)**，专为在线分析处理 (OLAP) 设计。

### 核心特点
- **极致性能**：单机每秒可处理数亿行数据
- **列式存储**：按列而不是按行存储数据，查询时只读取需要的列
- **向量化执行**：利用 CPU SIMD 指令并行处理数据
- **压缩率高**：列式存储天然适合压缩，通常能减少 5-10 倍存储空间
- **实时写入**：支持高吞吐量的实时数据写入

## 为什么在 AgenticHarness 中使用 ClickHouse？

在我们的量化交易系统中，ClickHouse 主要用于：

1. **历史 K 线数据存储** - 存储海量的历史价格数据（开高低收、成交量）
2. **高效回测** - 快速查询多年的历史数据进行策略回测  
3. **技术指标计算** - 利用内置函数快速计算 RSI、MACD 等指标
4. **时序数据分析** - 分析市场行为模式和趋势

对比传统关系型数据库：
- MySQL/PostgreSQL：适合 OLTP（在线事务处理），但处理大量历史数据查询较慢
- ClickHouse：专为 OLAP（在线分析处理）优化，查询速度提升 100-1000 倍

## 基础概念

### 表引擎 (Table Engines)
ClickHouse 的核心是**表引擎**，不同的引擎适用于不同场景：

- **MergeTree**：最常用的引擎，支持主键索引、分区、数据去重
- **ReplacingMergeTree**：自动去重相同主键的数据
- **SummingMergeTree**：自动聚合相同主键的数据

我们的项目使用 `MergeTree` 引擎。

### 数据类型
- `Date`：日期类型（年月日）
- `DateTime`：日期时间类型  
- `Float64`：双精度浮点数（用于价格）
- `String`：字符串（用于股票代码）
- `UInt64`：无符号64位整数（用于成交量）

## 实际操作 Demo

### 1. 启动 ClickHouse
```bash
# 在项目根目录启动
docker-compose up -d clickhouse

# 进入 ClickHouse 客户端
docker exec -it harness-clickhouse clickhouse-client
```

### 2. 基本 SQL 操作
```sql
-- 创建数据库
CREATE DATABASE agentic;

-- 使用数据库
USE agentic;

-- 创建 K 线表
CREATE TABLE kline_day (
    symbol String,
    date Date,
    open Float64,
    high Float64,
    low Float64, 
    close Float64,
    volume UInt64
) ENGINE = MergeTree()
ORDER BY (symbol, date);

-- 插入测试数据
INSERT INTO kline_day VALUES 
('000001', '2024-01-01', 10.0, 10.5, 9.8, 10.2, 1000000),
('000001', '2024-01-02', 10.2, 10.8, 10.1, 10.6, 1200000);

-- 查询数据
SELECT * FROM kline_day WHERE symbol = '000001' ORDER BY date;

-- 计算简单统计
SELECT 
    symbol,
    count() as days,
    avg(close) as avg_price,
    max(high) as highest,
    min(low) as lowest
FROM kline_day 
WHERE symbol = '000001'
GROUP BY symbol;
```

### 3. Python 连接示例
```python
from clickhouse_driver import Client
import pandas as pd

# 创建连接
client = Client(host='localhost', database='agentic')

# 查询数据到 DataFrame
query = "SELECT * FROM kline_day WHERE symbol = '000001' ORDER BY date"
df = pd.DataFrame(client.execute(query), 
                  columns=['symbol', 'date', 'open', 'high', 'low', 'close', 'volume'])

print(df.head())
```

### 4. 回测查询示例
```sql
-- 获取双均线回测需要的数据
SELECT 
    date,
    close,
    -- 计算5日均线
    avg(close) OVER (ORDER BY date ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) as ma5,
    -- 计算20日均线  
    avg(close) OVER (ORDER BY date ROWS BETWEEN 19 PRECEDING AND CURRENT ROW) as ma20
FROM kline_day 
WHERE symbol = '000001' 
  AND date >= '2023-01-01'
ORDER BY date;
```

## 性能优化技巧

### 1. 分区 (Partitioning)
```sql
-- 按月份分区，提高大表查询性能
CREATE TABLE kline_day_partitioned (
    symbol String,
    date Date,
    open Float64,
    high Float64,
    low Float64,
    close Float64,
    volume UInt64
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(date)  -- 按年月分区
ORDER BY (symbol, date);
```

### 2. 投影 (Projections) - ClickHouse 22.8+
```sql
-- 为常用查询模式创建投影
ALTER TABLE kline_day 
ADD PROJECTION fast_symbol_date (SELECT * ORDER BY symbol, date);
```

### 3. 物化视图
```sql
-- 创建预计算的技术指标视图
CREATE MATERIALIZED VIEW kline_indicators 
ENGINE = MergeTree() 
ORDER BY (symbol, date) AS
SELECT
    symbol,
    date,
    close,
    -- 预计算 RSI
    rsi(close, 14) as rsi_14,
    -- 预计算 MACD
    macd(close) as macd_line
FROM kline_day;
```

## 常见问题和解决方案

### Q: 写入性能慢怎么办？
A: 
- 使用批量插入（每次插入数千行而不是逐行插入）
- 调整 `max_insert_block_size` 参数
- 避免过于频繁的小批量写入

### Q: 内存不足怎么办？  
A:
- 调整 `max_memory_usage` 限制
- 使用 `LIMIT` 限制查询结果集大小
- 优化查询避免全表扫描

### Q: 如何监控 ClickHouse 性能？
A: 查询系统表：
```sql
-- 查看慢查询
SELECT query, elapsed, read_rows FROM system.query_log 
WHERE type = 2 ORDER BY elapsed DESC LIMIT 10;

-- 查看表大小
SELECT table, sum(bytes) as size FROM system.parts 
WHERE database = 'agentic' GROUP BY table;
```

## 在 AgenticHarness 中的具体应用

### 1. 数据表设计
```sql
-- 我们实际使用的表结构
CREATE TABLE kline_1min (
    symbol String,
    timestamp DateTime,
    open Float64,
    high Float64, 
    low Float64,
    close Float64,
    volume UInt64
) ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(timestamp)
ORDER BY (symbol, timestamp);
```

### 2. 回测服务集成
Java 后端通过 JDBC 连接 ClickHouse：
```java
// application.yml
spring:
  datasource:
    url: jdbc:clickhouse://localhost:8123/agentic
    driver-class-name: com.clickhouse.jdbc.ClickHouseDriver
```

### 3. 性能目标
- **回测速度**：1.2 秒/年（日线数据，5000只股票）
- **查询延迟**：P99 < 100ms
- **数据容量**：支持 10 年历史数据，5000+ 股票

通过合理使用 ClickHouse，我们可以实现高效的量化回测和实时分析！