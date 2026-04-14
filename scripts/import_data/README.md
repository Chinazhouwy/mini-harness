# Stock Data Import Scripts

This directory contains scripts for importing Chinese stock data into ClickHouse using the `mootdx` library.

## ✅ Successfully Imported Data

On **2026-04-14**, the following 5 stocks were imported into **`harness_db.daily_kline`** from **2025-04-14 to 2026-04-14**:

> **Note**: Data is stored in the `harness_db` database, not the `default` database. The `default` database contains older test data.

| Stock Code | Name | Records |
|------------|------|---------|
| 600406 | 国电南瑞 | 243 |
| 002463 | 沪电股份 | 243 |
| 300308 | 中际旭创 | 243 |
| 601899 | 紫金矿业 | 243 |
| 600900 | 长江电力 | 243 |

**Total**: 1,215 records

## 📊 Table Schema

The data is stored in the `daily_kline` table in ClickHouse with this schema:

```sql
CREATE TABLE daily_kline (
    trade_date Date,
    stock_code String,
    pre_close Decimal64(4),
    open Decimal64(4),
    high Decimal64(4),
    low Decimal64(4),
    close Decimal64(4),
    volume UInt64,
    amount Decimal64(8),
    turnover_rate Nullable(Decimal64(4)),
    change Decimal64(4),
    change_pct Decimal64(4),
    amplitude Decimal64(4),
    fetch_time DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (stock_code, trade_date)
PARTITION BY toYYYYMM(trade_date)
TTL trade_date + INTERVAL 10 YEAR
```

## 🚀 How to Use

### Prerequisites
1. ClickHouse running in Docker (see `docker-compose-mac.yml`)
2. Python dependencies: `mootdx`, `clickhouse-connect`, `pandas`

### Import New Data
```bash
# Run the simple import script
python import_stocks_simple.py

# Or run the original script (now fixed)
python import_selected.py
```

### Verify Data
```bash
# Check imported data
python verify_data.py
```

## 🔧 Key Learnings

1. **mootdx column mapping**: The library returns `vol` instead of `volume`
2. **Date handling**: The index is a `DatetimeIndex` that needs to be converted to date
3. **ClickHouse connection**: Use `harness_db` database with `default/harness123` credentials
4. **Calculated fields**: `pre_close`, `change`, `change_pct`, and `amplitude` are calculated from the raw data

## 📈 Sample Data

Here's a sample of the imported data:

```
2025-04-14 002463 O:28.71 H:28.91 L:27.85 C:27.96 V:666,420 A:1,885,981,952
2025-04-15 002463 O:27.80 H:27.89 L:27.34 C:27.54 V:418,612 A:1,154,572,672
2025-04-16 002463 O:27.31 H:27.35 L:26.04 C:26.44 V:651,893 A:1,731,457,024
```

## 🎯 Next Steps

1. **Incremental updates**: Modify script for daily updates instead of full reload
2. **More stocks**: Add configuration file for stock list
3. **Error handling**: Add retry logic and better error reporting
4. **Monitoring**: Add logging and performance metrics

## 📋 待改进计划 (Pending Improvements)

### 第一阶段：技能标准化 (Phase 1: Skill Standardization)
- [ ] **创建规范的技能结构** - 移动到 `.opencode/skill/import-a-stocks/` 目录
- [ ] **添加配置模板** - 创建 `config.yaml.example` 包含所有选项
- [ ] **创建requirements.txt** - 包含固定的依赖版本
- [ ] **添加setup.py** - 支持pip安装
- [ ] **更新安装说明** - 如何作为技能安装和使用

### 第二阶段：功能增强 (Phase 2: Functionality Enhancement)
- [ ] **实现增量更新** - 只获取上次导入后的新数据，避免全量重载
- [ ] **添加市场日历支持** - 自动跳过非交易日（周末、节假日）
- [ ] **改进错误处理** - 更完善的日志记录、恢复机制和告警
- [ ] **添加数据验证** - 插入前验证数据质量（价格连续性、成交量合理性等）
- [ ] **支持多种数据频率** - 除日线外，支持分钟线、周线、月线
- [ ] **添加进度条和状态显示** - 更友好的用户界面

### 第三阶段：测试与文档 (Phase 3: Testing & Documentation)
- [ ] **添加单元测试** - 关键功能测试（数据转换、计算逻辑等）
- [ ] **创建集成测试** - 模拟TDX API的集成测试环境
- [ ] **性能测试** - 测试大数据量导入性能
- [ ] **更新API文档** - 为所有函数添加完整的docstring和类型提示
- [ ] **创建使用示例** - 多种场景的使用示例代码
- [ ] **添加故障排查指南** - 常见问题及解决方案

### 第四阶段：项目集成 (Phase 4: Project Integration)
- [ ] **添加到项目技能注册表** - 如果项目有技能注册机制
- [ ] **创建Docker镜像** - 支持独立执行的Docker容器
- [ ] **添加到CI/CD流水线** - 自动化测试和部署
- [ ] **创建Web API接口** - 提供HTTP API调用能力
- [ ] **添加监控指标** - Prometheus指标导出
- [ ] **完善配置管理** - 支持环境变量、配置文件、命令行参数多层配置

### 第五阶段：高级特性 (Phase 5: Advanced Features)
- [ ] **多数据源支持** - 支持除mootdx外的其他数据源
- [ ] **数据质量检查** - 自动检测异常数据点
- [ ] **备份与恢复** - 数据备份和恢复机制
- [ ] **分布式部署** - 支持多节点并行导入
- [ ] **数据补全** - 自动修复缺失的历史数据
- [ ] **实时数据流** - 支持实时行情数据导入

## 🛠️ 技术债务 (Technical Debt)

### 高优先级
- [ ] 修复import_selected.py中的语法错误（第334行缺少括号）
- [ ] 统一两个脚本的接口和实现
- [ ] 添加必要的参数验证和类型检查

### 中优先级
- [ ] 改进数据列映射逻辑，使其更健壮
- [ ] 添加连接池管理，避免频繁创建销毁连接
- [ ] 优化内存使用，支持大数据量导入

### 低优先级
- [ ] 代码重构，提取公共组件
- [ ] 添加更多注释和文档字符串
- [ ] 优化导入性能

## 📈 版本规划 (Version Planning)

### v1.0 (当前)
- 基本功能：5只股票数据导入 ✅
- 简单配置：命令行参数 ✅
- 基础错误处理：重试机制 ✅

### v1.1 (下一步)
- 完整技能封装
- 配置文件支持
- 增量更新功能

### v1.2
- 单元测试覆盖
- 性能优化
- 扩展股票列表

### v2.0
- 多数据源支持
- Web API接口
- Docker化部署