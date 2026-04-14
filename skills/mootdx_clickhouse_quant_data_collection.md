# 🛠️ Skill: A股量化数据批量采集（基于mootdx，无需通达信客户端）
**适用场景**：云主机7×24小时批量采集全市场A股日K/分钟K/分时/财务数据、每日增量更新、无本地通达信依赖场景

---
## 📋 前置条件
1. 环境：Python 3.8+、Linux云主机（2核4G以上、带宽≥1M）
2. 依赖：`mootdx`、`clickhouse-connect`、`pandas`、`numpy`
3. 存储：ClickHouse（推荐）/ PostgreSQL / 本地文件
4. 网络：公网连接，**无需安装通达信客户端**

---
## 🎯 核心能力
### 1. 环境配置能力
```python
# 安装依赖
pip install mootdx clickhouse-connect pandas numpy

# 初始化客户端（默认在线模式，不需要通达信）
from mootdx.quotes import Quotes
tdx_client = Quotes.factory(market='std') # 沪深京市场

# 初始化存储
import clickhouse_connect
ch_client = clickhouse_connect.get_client(
    host='localhost', port=8123, 
    username='default', password='your-password'
)
```

### 2. 全量历史数据下载流程
| 步骤 | 操作 | 耗时参考 |
|---|---|---|
| 1 | 获取全市场股票列表 | <1分钟 |
| 2 | 按股票代码分片批量下载日K/分钟K | 全市场日K：1-2小时；全市场分钟K：24-48小时 |
| 3 | 数据清洗（去重、日期格式修正、指标计算） | 总耗时的10% |
| 4 | 批量写入存储 | 总耗时的5% |
| 5 | 一致性校验 | 总耗时的5% |

**关键代码示例**：
```python
import time, random, json
from multiprocessing import Pool

# 1. 获取全市场股票列表
from mootdx.consts import MARKET_SH, MARKET_SZ
sh_stocks = tdx_client.stocks(market=MARKET_SH)
sz_stocks = tdx_client.stocks(market=MARKET_SZ)
all_stocks = list(set(sh_stocks['code'].tolist() + sz_stocks['code'].tolist()))

# 2. 断点续传记录
progress = json.load(open('download_progress.json', 'r')) if os.path.exists('download_progress.json') else {}

def download_stock(stock_code):
    if stock_code in progress:
        return None # 已经下载过的跳过
    
    # 随机延迟，避免被封
    time.sleep(random.uniform(0.5, 2))
    
    # 下载上市至今全部日K
    try:
        kline_data = tdx_client.bars(symbol=stock_code, frequency=9, offset=5000) # 最多5000条=20年数据
        if kline_data is not None and not kline_data.empty:
            # 数据清洗
            kline_data['stock_code'] = stock_code
            kline_data['trade_date'] = kline_data.index.date
            # 写入ClickHouse
            ch_client.insert_df('daily_kline', kline_data)
            # 更新进度
            progress[stock_code] = True
            json.dump(progress, open('download_progress.json', 'w'))
            return len(kline_data)
    except Exception as e:
        print(f"{stock_code} 下载失败: {e}")
        return None

# 3. 多进程下载（4-8进程最佳）
with Pool(processes=4) as pool:
    results = pool.map(download_stock, all_stocks)
```

### 3. 每日增量更新流程
| 步骤 | 操作 | 耗时参考 |
|---|---|---|
| 1 | 每日15:30（收盘后）启动任务 | - |
| 2 | 下载全市场当日日K/分钟K数据 | 5-10分钟（日K）、30-60分钟（分钟K） |
| 3 | 合并写入存储 | 1-2分钟 |
| 4 | 校验数据完整性 | 1分钟 |

**关键代码示例**：
```python
# 每日增量更新只需下载最近1条数据
def update_daily_data(stock_code):
    time.sleep(random.uniform(0.1, 0.5))
    kline_data = tdx_client.bars(symbol=stock_code, frequency=9, offset=1)
    if kline_data is not None and len(kline_data) >= 1:
        kline_data['stock_code'] = stock_code
        kline_data['trade_date'] = kline_data.index.date
        # 先删除当日已有数据避免重复
        ch_client.command(f"DELETE FROM daily_kline WHERE stock_code = '{stock_code}' AND trade_date = toDate(now())")
        ch_client.insert_df('daily_kline', kline_data)
```

### 4. 数据质量校验能力
```python
# 校验每日数据完整性
def validate_daily_data():
    # 当日应该有数据的股票数量
    total_stocks = ch_client.query("SELECT COUNT(DISTINCT stock_code) FROM stock_info WHERE is_active = 1").result_rows[0][0]
    # 实际有数据的股票数量
    actual_count = ch_client.query("SELECT COUNT(DISTINCT stock_code) FROM daily_kline WHERE trade_date = today()").result_rows[0][0]
    # 完整性率达到99%以上为合格
    return actual_count / total_stocks >= 0.99
```

### 5. 异常处理能力
- **IP封禁**：配置代理池、降低请求频率（≤1次/秒）、更换服务器IP
- **数据重复**：写入前按`股票代码+交易日期`去重；批量写入前执行DELETE避免重复
- **请求失败**：重试3次，仍失败则加入重试队列，次日补采
- **服务器宕机**：断点续传机制，重启后自动从上次进度继续
- **并发写入**：多进程写入时使用任务分片，确保每个股票-日期仅由一个进程处理

---
## ⚡ 最佳实践
1. **请求控制**：单IP请求频率控制在1次/秒以内，避免被通达信服务器封禁
2. **进程数量**：下载进程控制在4-8个，过多反而会被封IP
3. **存储优化**：ClickHouse按月份分区、建立联合索引，查询速度提升100倍
4. **备份策略**：每周全量备份一次数据到对象存储，避免数据丢失
5. **监控告警**：下载失败率超过5%、数据完整性低于99%时触发告警

---
## 📦 ClickHouse存储对接（必选）
### 1. 连接配置（支持本地/远程/云ClickHouse）
```python
import clickhouse_connect

# --------------------------
# 连接方式1：本地ClickHouse
# --------------------------
ch_client = clickhouse_connect.get_client(
    host='127.0.0.1',
    port=8123,
    username='default',
    password='harness123',
    database='quant_data' # 提前创建的数据库，默认可以用default
)

# --------------------------
# 连接方式2：云主机远程连接
# --------------------------
ch_client = clickhouse_connect.get_client(
    host='你的云主机公网IP',
    port=8123,
    username='default',
    password='harness123',
    database='quant_data',
    secure=False # 非SSL连接用这个，HTTPS的话设为True
)

# --------------------------
# 连接校验
# --------------------------
print(ch_client.query("SELECT version()").result_rows[0][0]) # 输出版本号则连接成功
``` 

---
### 2. 标准表结构设计（和mootdx返回字段完美适配）
#### （1）股票基本信息表 `stock_info`
```sql
CREATE TABLE IF NOT EXISTS stock_info
(
    stock_code String COMMENT '股票代码 6位纯数字',
    short_name String COMMENT '股票简称',
    exchange String COMMENT '交易所 SH=上交所 SZ=深交所 BJ=北交所',
    market_cap Nullable(Decimal64(4)) COMMENT '总市值 亿元',
    industry Nullable(String) COMMENT '所属行业',
    listing_date Nullable(Date) COMMENT '上市日期',
    is_active UInt8 DEFAULT 1 COMMENT '是否交易中 1=正常 0=退市/停牌',
    update_time DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = MergeTree()
ORDER BY (exchange, stock_code)
PARTITION BY exchange;
```

#### （2）日K线表 `daily_kline`（最常用）
```sql
CREATE TABLE IF NOT EXISTS daily_kline
(
    trade_date Date COMMENT '交易日期',
    stock_code String COMMENT '股票代码',
    pre_close Decimal64(4) COMMENT '前收盘价 元',
    open Decimal64(4) COMMENT '开盘价',
    high Decimal64(4) COMMENT '最高价',
    low Decimal64(4) COMMENT '最低价',
    close Decimal64(4) COMMENT '收盘价',
    volume UInt64 COMMENT '成交量 股',
    amount Decimal64(8) COMMENT '成交金额 万元',
    turnover_rate Nullable(Decimal64(4)) COMMENT '换手率 %',
    change Decimal64(4) COMMENT '涨跌额 元',
    change_pct Decimal64(4) COMMENT '涨跌幅 %',
    amplitude Decimal64(4) COMMENT '振幅 %',
    fetch_time DateTime DEFAULT now() COMMENT '采集时间'
)
ENGINE = MergeTree()
ORDER BY (stock_code, trade_date)
PARTITION BY toYYYYMM(trade_date) -- 按月份分区，查询速度提升100倍
TTL trade_date + INTERVAL 10 YEAR; -- 保留10年数据，可调整
```

#### （2）日K线表 `daily_kline`（最常用）
```sql
CREATE TABLE IF NOT EXISTS daily_kline
(
    trade_date Date COMMENT '交易日期',
    stock_code String COMMENT '股票代码',
    pre_close Decimal64(4) COMMENT '前收盘价 元',
    open Decimal64(4) COMMENT '开盘价',
    high Decimal64(4) COMMENT '最高价',
    low Decimal64(4) COMMENT '最低价',
    close Decimal64(4) COMMENT '收盘价',
    volume UInt64 COMMENT '成交量 股',
    amount Decimal64(8) COMMENT '成交金额 万元',
    turnover_rate Nullable(Decimal64(4)) COMMENT '换手率 %',
    change Decimal64(4) COMMENT '涨跌额 元',
    change_pct Decimal64(4) COMMENT '涨跌幅 %',
    amplitude Decimal64(4) COMMENT '振幅 %',
    fetch_time DateTime DEFAULT now() COMMENT '采集时间'
)
ENGINE = MergeTree()
ORDER BY (stock_code, trade_date)
PARTITION BY toYYYYMM(trade_date) -- 按月份分区，查询速度提升100倍
TTL trade_date + INTERVAL 10 YEAR; -- 保留10年数据，可调整
```

#### （3）分钟K线表 `minute_kline`
```sql
CREATE TABLE IF NOT EXISTS minute_kline
(
    trade_time DateTime COMMENT '交易时间 精确到分钟',
    stock_code String COMMENT '股票代码',
    interval_type UInt8 COMMENT '周期 1=1分钟 5=5分钟 15=15分钟 30=30分钟 60=60分钟',
    open Decimal64(4) COMMENT '开盘价',
    high Decimal64(4) COMMENT '最高价',
    low Decimal64(4) COMMENT '最低价',
    close Decimal64(4) COMMENT '收盘价',
    volume UInt64 COMMENT '成交量 股',
    amount Decimal64(4) COMMENT '成交金额 万元',
    fetch_time DateTime DEFAULT now() COMMENT '采集时间'
)
ENGINE = MergeTree()
ORDER BY (stock_code, interval_type, trade_time)
PARTITION BY toYYYYMM(trade_time)
TTL trade_time + INTERVAL 3 YEAR;
```

#### （4）财务数据表 `finance_data`
```sql
CREATE TABLE IF NOT EXISTS finance_data
(
    report_date Date COMMENT '报告期',
    stock_code String COMMENT '股票代码',
    report_type String COMMENT '报告类型 Q1=一季报 Q2=半年报 Q3=三季报 FY=年报',
    total_revenue Nullable(Decimal64(4)) COMMENT '营业总收入 万元',
    net_profit Nullable(Decimal64(4)) COMMENT '净利润 万元',
    eps Nullable(Decimal64(4)) COMMENT '每股收益 元',
    roe Nullable(Decimal64(4)) COMMENT '净资产收益率 %',
    pe_ratio Nullable(Decimal64(4)) COMMENT '市盈率',
    pb_ratio Nullable(Decimal64(4)) COMMENT '市净率',
    update_time DateTime DEFAULT now() COMMENT '更新时间'
)
ENGINE = MergeTree()
ORDER BY (stock_code, report_date, report_type)
PARTITION BY toYYYYMM(report_date);
```

---
### 3. 三种数据导入方式
#### （1）直接DataFrame批量导入（最高效，推荐）
和mootdx采集代码无缝衔接：
```python
# kline_data是pandas DataFrame，字段和表结构完全对应
ch_client.insert_df(
    table_name='daily_kline',
    df=kline_data,
    settings={
        'async_insert': 1, # 异步插入提升性能
        'wait_for_async_insert': 0,
        'insert_allow_materialized_columns': 1
    }
)

# 批量大小建议：单次1000-10000条，不要单条插入，速度提升10倍
```

#### （2）CSV文件批量导入（适合离线数据迁移）
```bash
# 命令行导入
clickhouse-client --user default --password 你的密码 --query "INSERT INTO daily_kline FORMAT CSVWithNames" < 2024_daily_kline.csv

# Python代码导入
ch_client.command("INSERT INTO daily_kline FORMAT CSVWithNames FROM INFILE '/path/2024_daily_kline.csv'")
```

#### （3）增量更新导入（每日更新用，避免重复）
```python
def insert_increment_data(stock_code, kline_data):
    # 先删除当日已存在的数据，避免重复
    trade_date = kline_data.iloc[0]['trade_date']
    ch_client.command(f"""
    DELETE FROM daily_kline 
    WHERE stock_code = '{stock_code}' AND trade_date = '{trade_date}'
    """)
    # 再插入新数据
    ch_client.insert_df('daily_kline', kline_data)
```

---
### 4. 导入后校验方法
```python
# 校验数据量
def validate_insert(stock_code, trade_date, expected_rows=1):
    actual = ch_client.query(f"""
    SELECT COUNT(*) FROM daily_kline 
    WHERE stock_code = '{stock_code}' AND trade_date = '{trade_date}'
    """).result_rows[0][0]
    return actual == expected_rows

# 校验数据一致性
def validate_data_consistency(stock_code, start_date, end_date):
    # 计算区间内涨幅，对比实际值
    return ch_client.query(f"""
    SELECT (last_value(close) / first_value(close) - 1) * 100 
    FROM daily_kline 
    WHERE stock_code = '{stock_code}' AND trade_date BETWEEN '{start_date}' AND '{end_date}'
    """).result_rows[0][0]
```

---
### 5. 性能优化建议
| 优化项 | 配置 | 效果 |
|---|---|---|
| 批量写入 | 单次插入1000-10000条 | 写入速度提升10倍 |
| 分区索引 | 按月份分区，建立stock_code+trade_date联合索引 | 查询速度提升100倍 |
| 异步插入 | 开启async_insert | 写入吞吐量提升2倍 |
| 压缩配置 | 开启LZ4压缩 | 存储空间减少70% |

---
## ❌ 常见问题解决方案
| 问题 | 解决方案 |
|---|---|
| IP被封禁 | 配置`os.environ['HTTP_PROXY'] = 'http://代理地址:端口'`，或降低请求频率 |
| 日期格式错误 | 使用`kline_data.index.date`获取日期，不要用返回的datetime字段 |
| 数据重复 | 写入前执行`DELETE FROM 表 WHERE stock_code = 'xxx' AND trade_date = 'xxx'` |
| 下载速度慢 | 增加进程数到8个，或使用多IP/代理池 |
| ClickHouse公网连接失败 | 检查云主机安全组是否开放8123/9000端口、ClickHouse配置`config.xml`里`listen_host`设为`0.0.0.0` |
| 导入报字段不匹配 | 检查DataFrame的字段顺序、类型和表结构完全一致，可通过指定`column_names`参数解决 |
| 写入速度慢 | 增加单次批量大小、开启异步插入、减少不必要的索引 |
| 中文乱码 | 导入CSV时指定`encoding='utf-8'`，表字段用String类型 |
| 导入报权限错误 | 给对应用户授予对应数据库的写入权限：`GRANT INSERT ON quant_data.* TO 'default'@'%'` |

---
## 📊 数据量级参考
| 数据类型 | 全市场年数据量 | 日增量 |
|---|---|---|
| 日K线 | ~2GB/年 | ~5MB/天 |
| 1分钟K线 | ~100GB/年 | ~500MB/天 |
| 财务数据 | ~100MB/年 | ~10KB/天 |
| 分时数据 | ~50GB/年 | ~200MB/天 |

---
## ⚠️ 合规说明
- 本Skill基于开源免费的mootdx库，仅用于个人学习研究
- 数据来源为公开的通达信行情接口，请勿用于商业用途
- 批量下载时请遵守服务器使用规则，不要对公共服务造成压力