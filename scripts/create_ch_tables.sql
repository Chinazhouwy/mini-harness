-- ClickHouse 表结构设计 for A股量化交易数据
-- 使用在 harness_db 数据库中

USE harness_db;

-- 1. 股票基本信息表
CREATE TABLE IF NOT EXISTS stock_info
(
    stock_code String,           -- 股票代码，如 '000001'
    short_name String,           -- 股票简称，如 '平安银行'
    full_name Nullable(String),  -- 股票全称
    exchange String,             -- 交易所: 'SZ'（深交所）, 'SH'（上交所）, 'BJ'（北交所）
    market_cap Nullable(Decimal64(4)), -- 总市值（亿元）
    industry Nullable(String),   -- 行业分类
    region Nullable(String),     -- 地域
    listing_date Nullable(Date), -- 上市日期
    is_active UInt8 DEFAULT 1,   -- 是否活跃（1=活跃，0=退市）
    update_time DateTime DEFAULT now(), -- 更新时间
    
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1,
    INDEX idx_exchange exchange TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (exchange, stock_code)
PARTITION BY exchange;

-- 2. 日K线行情表（每日收盘后更新）
CREATE TABLE IF NOT EXISTS daily_kline
(
    trade_date Date,            -- 交易日期
    stock_code String,          -- 股票代码
    pre_close Decimal64(4),     -- 前收盘价
    open Decimal64(4),          -- 开盘价
    high Decimal64(4),          -- 最高价
    low Decimal64(4),           -- 最低价
    close Decimal64(4),         -- 收盘价
    volume UInt64,              -- 成交量（股）
    amount Decimal64(4),        -- 成交金额（万元）
    turnover_rate Nullable(Decimal64(4)), -- 换手率（%）
    change Decimal64(4),        -- 涨跌额
    change_pct Decimal64(4),    -- 涨跌幅（%）
    amplitude Decimal64(4),     -- 振幅（%）
    update_time DateTime DEFAULT now(), -- 更新时间
    
    INDEX idx_trade_date trade_date TYPE minmax GRANULARITY 1,
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1,
    INDEX idx_date_code (trade_date, stock_code) TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (stock_code, trade_date)
PARTITION BY toYYYYMM(trade_date)
TTL trade_date + INTERVAL 5 YEAR; -- 保留5年数据

-- 3. 分钟K线行情表（1分钟、5分钟、15分钟、30分钟、60分钟）
CREATE TABLE IF NOT EXISTS minute_kline
(
    trade_time DateTime,        -- 交易时间（精确到分钟）
    stock_code String,          -- 股票代码
    interval_type UInt8,        -- 时间间隔: 1=1分钟, 5=5分钟, 15=15分钟, 30=30分钟, 60=60分钟
    open Decimal64(4),          -- 开盘价
    high Decimal64(4),          -- 最高价
    low Decimal64(4),           -- 最低价
    close Decimal64(4),         -- 收盘价
    volume UInt64,              -- 成交量（股）
    amount Decimal64(4),        -- 成交金额（万元）
    update_time DateTime DEFAULT now(), -- 更新时间
    
    INDEX idx_trade_time trade_time TYPE minmax GRANULARITY 1,
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1,
    INDEX idx_interval interval_type TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (stock_code, interval_type, trade_time)
PARTITION BY toYYYYMM(trade_time)
TTL trade_time + INTERVAL 1 YEAR; -- 保留1年分钟数据

-- 4. Level2 逐笔成交表（如果获取到高频数据）
CREATE TABLE IF NOT EXISTS level2_tick
(
    trade_time DateTime64(3),   -- 交易时间（毫秒精度）
    stock_code String,          -- 股票代码
    price Decimal64(4),         -- 成交价格
    volume UInt32,              -- 成交数量（股）
    amount Decimal64(4),        -- 成交金额（元）
    direction Int8,             -- 买卖方向: 1=买, -1=卖, 0=中性
    trade_type String,          -- 交易类型: '限价委托', '市价委托'等
    ask_price Nullable(Decimal64(4)), -- 卖一价
    bid_price Nullable(Decimal64(4)), -- 买一价
    update_time DateTime DEFAULT now(), -- 更新时间
    
    INDEX idx_trade_time trade_time TYPE minmax GRANULARITY 1,
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (stock_code, trade_time)
PARTITION BY toYYYYMM(trade_time)
TTL trade_time + INTERVAL 30 DAY; -- Level2数据量很大，只保留30天

-- 5. 财务数据表（季度/年度）
CREATE TABLE IF NOT EXISTS financial_data
(
    report_date Date,           -- 报告期
    stock_code String,          -- 股票代码
    report_type String,         -- 报告类型: 'Q1', 'Q2', 'Q3', 'Q4', 'FY'（年报）
    total_revenue Nullable(Decimal64(4)), -- 营业总收入（万元）
    net_profit Nullable(Decimal64(4)),    -- 净利润（万元）
    eps Nullable(Decimal64(4)),           -- 每股收益（元）
    roe Nullable(Decimal64(4)),           -- 净资产收益率（%）
    total_assets Nullable(Decimal64(4)),  -- 总资产（万元）
    total_liabilities Nullable(Decimal64(4)), -- 总负债（万元）
    operating_cash_flow Nullable(Decimal64(4)), -- 经营活动现金流（万元）
    pe_ratio Nullable(Decimal64(4)),      -- 市盈率
    pb_ratio Nullable(Decimal64(4)),      -- 市净率
    update_time DateTime DEFAULT now(),   -- 更新时间
    
    INDEX idx_report_date report_date TYPE minmax GRANULARITY 1,
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1,
    INDEX idx_report_type report_type TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (stock_code, report_date, report_type)
PARTITION BY toYYYYMM(report_date);

-- 6. 指数数据表（上证指数、深证成指等）
CREATE TABLE IF NOT EXISTS index_data
(
    trade_date Date,            -- 交易日期
    index_code String,          -- 指数代码，如 '000001'（上证指数）
    index_name String,          -- 指数名称
    pre_close Decimal64(4),     -- 前收盘
    open Decimal64(4),          -- 开盘
    high Decimal64(4),          -- 最高
    low Decimal64(4),           -- 最低
    close Decimal64(4),         -- 收盘
    volume UInt64,              -- 成交量
    amount Decimal64(4),        -- 成交金额
    change Decimal64(4),        -- 涨跌额
    change_pct Decimal64(4),    -- 涨跌幅（%）
    update_time DateTime DEFAULT now(), -- 更新时间
    
    INDEX idx_trade_date trade_date TYPE minmax GRANULARITY 1,
    INDEX idx_index_code index_code TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (index_code, trade_date)
PARTITION BY toYYYYMM(trade_date);

-- 7. 概念板块信息表
CREATE TABLE IF NOT EXISTS concept_info
(
    concept_code String,        -- 概念代码
    concept_name String,        -- 概念名称
    source String,              -- 数据源: 'ths'（同花顺）, 'east'（东方财富）
    description Nullable(String), -- 概念描述
    update_time DateTime DEFAULT now(), -- 更新时间
    
    INDEX idx_concept_code concept_code TYPE minmax GRANULARITY 1,
    INDEX idx_concept_name concept_name TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (source, concept_code);

-- 8. 概念成分股关系表
CREATE TABLE IF NOT EXISTS concept_constituent
(
    concept_code String,        -- 概念代码
    stock_code String,          -- 成分股代码
    weight Nullable(Decimal64(4)), -- 权重（如果有）
    update_time DateTime DEFAULT now(), -- 更新时间
    
    INDEX idx_concept_code concept_code TYPE minmax GRANULARITY 1,
    INDEX idx_stock_code stock_code TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree()
ORDER BY (concept_code, stock_code);

-- 创建物化视图用于快速查询（可选）
-- 例如：创建每日涨跌幅排行榜的物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS daily_change_rank_mv
ENGINE = MergeTree()
ORDER BY (trade_date, change_pct DESC)
AS SELECT
    trade_date,
    stock_code,
    close,
    change,
    change_pct,
    volume,
    amount
FROM daily_kline
WHERE trade_date >= today() - 30; -- 只保留最近30天