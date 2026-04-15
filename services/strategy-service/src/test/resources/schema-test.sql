-- 创建stock_info表结构
CREATE TABLE IF NOT EXISTS harness_db.stock_info
(
    stock_code   VARCHAR(10) NOT NULL,
    short_name   VARCHAR(50),
    full_name    VARCHAR(200),
    exchange     VARCHAR(10),
    market_cap   DECIMAL(20, 4),
    industry     VARCHAR(100),
    region       VARCHAR(50),
    listing_date DATE,
    is_active    INT,
    update_time  DATETIME DEFAULT now(),
    PRIMARY KEY (stock_code)
) ENGINE = MergeTree()
ORDER BY stock_code;

-- 创建daily_kline表结构
CREATE TABLE IF NOT EXISTS harness_db.daily_kline
(
    trade_date      DATE NOT NULL,
    stock_code      VARCHAR(10) NOT NULL,
    pre_close       DECIMAL(12, 4),
    open            DECIMAL(12, 4),
    high            DECIMAL(12, 4),
    low             DECIMAL(12, 4),
    close           DECIMAL(12, 4),
    volume          BIGINT,
    amount          DECIMAL(20, 4),
    turnover_rate   DECIMAL(10, 4),
    change          DECIMAL(12, 4),
    change_pct      DECIMAL(10, 4),
    amplitude       DECIMAL(10, 4),
    update_time     DATETIME DEFAULT now(),
    PRIMARY KEY (trade_date, stock_code)
) ENGINE = MergeTree()
ORDER BY (stock_code, trade_date)
PARTITION BY toYYYYMM(trade_date);

-- 创建minute_kline表结构
CREATE TABLE IF NOT EXISTS harness_db.minute_kline
(
    timestamp       DATETIME NOT NULL,
    stock_code      VARCHAR(10) NOT NULL,
    open_price      DECIMAL(12, 4),
    high_price      DECIMAL(12, 4),
    low_price       DECIMAL(12, 4),
    close_price     DECIMAL(12, 4),
    volume          BIGINT,
    turnover        DECIMAL(20, 4),
    created_at      DATETIME DEFAULT now(),
    PRIMARY KEY (timestamp, stock_code)
) ENGINE = MergeTree()
ORDER BY (stock_code, timestamp)
PARTITION BY toYYYYMM(timestamp);