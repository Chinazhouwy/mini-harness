
## 🗓️ 2026年4月7日任务计划（第1周 - 第1天）

### 上午任务（9:00-12:00）

#### 1. 启动基础设施 ✅ (30分钟)
```bash
cd /Users/chinazhouwy/doc/quantHarness
docker-compose up -d clickhouse redis
docker-compose ps  # 验证服务状态
```

#### 2. 验证 ClickHouse 连接 ✅ (30分钟)
```bash
# 测试 ClickHouse 连接
docker exec -it harness-clickhouse clickhouse-client
# 执行：SELECT version();
```

#### 3. 创建历史数据表 ✅ (60分钟)
- 在 `scripts/` 目录下创建 `create_tables.sql`
- 创建 `kline_day` 表结构：
  ```sql
  CREATE DATABASE IF NOT EXISTS agentic;
  USE agentic;
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
  ```

### 下午任务（14:00-18:00）

#### 4. 编写数据获取脚本 ✅ (120分钟)
- 在 `agents/` 目录下创建 `data_fetcher.py`
- 实现功能：
  - 使用 akshare 获取股票 `000001` 最近2年日线数据
  - 连接 ClickHouse 并插入数据
  - 包含错误处理和重试机制

#### 5. 测试数据获取 ✅ (60分钟)
```bash
cd agents
python data_fetcher.py --symbol 000001 --days 730
```

#### 6. 验证数据完整性 ✅ (30分钟)
```sql
-- 在 ClickHouse 中执行
USE agentic;
SELECT count(*) FROM kline_day WHERE symbol = '000001';
SELECT * FROM kline_day WHERE symbol = '000001' ORDER BY date DESC LIMIT 5;
```

### 晚间任务（可选）

#### 7. 提交代码和文档 ✅ (30分钟)
- 提交今日代码到 Git
- 更新 README.md 记录今日进展
- 记录遇到的问题和解决方案

---

### 🎯 今日成功标准
- [ ] ClickHouse 和 Redis 正常运行
- [ ] `kline_day` 表创建成功  
- [ ] 股票 `000001` 的历史数据成功存入 ClickHouse
- [ ] 数据记录数 > 500 条（2年交易日）
- [ ] 代码已提交并有相应文档

### ⚠️ 注意事项
1. **网络问题**：akshare 可能需要科学上网，如遇问题可先用本地 CSV 文件测试
2. **依赖安装**：确保 Python 环境有 akshare、pandas、clickhouse-driver
3. **数据验证**：务必验证数据的完整性和准确性
4. **错误处理**：脚本要有完善的异常处理，避免中断
