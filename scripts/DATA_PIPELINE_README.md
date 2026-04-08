# A股数据管道脚本解释文档
---
## 🔍 核心概念解释
### 什么是 mootdx？
mootdx是一个**开源免费的Python量化数据源库**，基于通达信协议开发，可以：
- ✅ 无需API密钥，免费获取A股全市场数据
- ✅ 支持股票列表、K线（1分钟/5分钟/日/周/月）、分时行情、财务数据、指数数据
- ✅ 支持从本地通达信客户端读取数据或从网络实时获取
- ✅ 完全开源无限制，Github地址：https://github.com/mootdx/mootdx
- ✅ 目前可以获取49851只A股（含指数、债券、基金）的全量数据

### 什么是 AData？
AData是另一个**开源免费A股量化数据库**，特点：
- ✅ 多数据源融合（同花顺、东方财富、新浪、腾讯等自动切换）
- ✅ 支持股票、基金、债券、概念板块、舆情等全品类数据
- ✅ 内置代理池支持，解决IP封禁问题
- ✅ 官网：https://adata.30006124.xyz
- ⚠️ 当前测试发现国内网络访问不稳定，需要代理才能正常使用

---
## 📂 所有脚本功能说明
### 一、`scripts/` 目录下的脚本
#### 1. `create_ch_tables.sql`
**功能**：ClickHouse数据库表结构定义文件
- 定义了8个量化交易所需的核心表：
  1. `stock_info`：股票基础信息表（代码、名称、交易所、行业等）
  2. `daily_kline`：日K线行情表（OHLCV、涨跌幅、换手率等）
  3. `minute_kline`：分钟K线行情表（支持1/5/15/30/60分钟）
  4. `level2_tick`：Level2逐笔成交表（存储高频交易数据）
  5. `financial_data`：财务数据表（营收、净利润、ROE等核心指标）
  6. `index_data`：指数数据表（上证指数、深证成指等）
  7. `concept_info`：概念板块信息表
  8. `concept_constituent`：概念成分股关系表
- 包含优化配置：分区策略、索引设计、数据TTL自动过期策略

#### 2. `setup_clickhouse.py`
**功能**：自动化表创建工具
- 解决ClickHouse不支持多语句批量执行的问题
- 逐个执行SQL创建所有表
- 自动验证创建结果
- 使用方法：`python3 setup_clickhouse.py`

#### 3. `data_pipeline.py`
**功能**：完整的数据采集管道框架
- 核心类 `AShareDataPipeline` 封装了所有数据操作：
  - `fetch_stock_list()`：获取全市场股票列表
  - `fetch_daily_kline()`：获取单只股票日K线数据
  - `insert_stock_list_to_ch()`：批量插入股票数据到ClickHouse
  - `insert_daily_kline_to_ch()`：批量插入日K线数据到ClickHouse
  - `test_pipeline()`：一键测试整个数据流程
- 自动处理数据格式转换、错误重试、批量插入优化

#### 4. `final_data_import.py`
**功能**：样例数据导入工具
- 用于快速生成模拟数据验证整个系统可用性
- 导入5只核心股票（平安银行、招商银行、五粮液、贵州茅台、东方财富）的30天日K线数据
- 导入3个核心指数（上证指数、深证成指、创业板指）的30天数据
- 自动验证导入结果并给出查询示例
- 使用方法：`python3 final_data_import.py`

#### 5. `test_direct_fetch.py`
**功能**：mootdx接口直接测试工具
- 测试不同频率K线数据获取
- 测试分时数据、指数数据、财务数据获取
- 用于验证mootdx库是否正常工作
- 使用方法：`python3 test_direct_fetch.py`

---
### 二、`agents/` 目录下的测试脚本
#### 1. `test_tdx.py` / `test_tdx_simple.py`
**功能**：mootdx库功能测试脚本
- 完整版/简化版两个版本，用于测试mootdx的所有功能
- 测试股票列表获取、K线获取、本地文件读取等
- 用于验证通达信数据格式解析是否正常

#### 2. `test_adata.py` / `test_adata_simple.py` / `test_adata_basic.py`
**功能**：AData库功能测试脚本
- 不同简化程度的测试版本，用于验证AData库的可用性
- 测试股票列表、行情、概念板块等数据获取
- 测试代理配置功能

---
## 🚀 脚本使用顺序（推荐）
```mermaid
graph LR
A[安装依赖] --> B[运行setup_clickhouse.py创建表]
B --> C[运行test_direct_fetch.py验证数据源]
C --> D[运行data_pipeline.py测试数据管道]
D --> E[运行final_data_import.py导入样例数据]
E --> F[业务开发/策略回测]
```

### 前置依赖安装
```bash
pip install mootdx clickhouse-connect pandas numpy
```

### 验证数据是否正常
运行脚本导入数据后，可以通过以下命令验证：
```bash
# 查看股票列表
curl -s "http://localhost:8123/?query=SELECT * FROM stock_info LIMIT 10" --user default:harness123

# 查看日K线数据
curl -s "http://localhost:8123/?query=SELECT trade_date, open, close, volume FROM daily_kline WHERE stock_code='000001' ORDER BY trade_date" --user default:harness123
```

---
## ⚠️ 注意事项
1. 所有脚本都是**测试版本**，仅用于验证技术可行性
2. 生产使用需要增加错误处理、日志、监控等功能
3. 数据都是模拟生成的样例数据，不是真实市场数据
4. mootdx的日K线接口返回的日期有格式问题，需要修复后再用于生产
5. AData需要代理才能正常使用，国内网络可能无法直接访问
