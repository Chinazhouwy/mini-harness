CREATE TABLE IF NOT EXISTS backtest_task (
    task_id VARCHAR(64) PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    fast_window INT NOT NULL,
    slow_window INT NOT NULL,
    initial_cash NUMERIC(18, 4) NOT NULL,
    max_position_ratio NUMERIC(8, 4) NOT NULL,
    stop_loss_ratio NUMERIC(8, 4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    final_equity NUMERIC(18, 4),
    total_return NUMERIC(18, 4),
    max_drawdown NUMERIC(18, 4),
    trade_count INT,
    winning_trades INT,
    win_rate NUMERIC(18, 4),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS backtest_signal (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES backtest_task(task_id) ON DELETE CASCADE,
    trade_date DATE NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    signal_type VARCHAR(10) NOT NULL,
    price NUMERIC(18, 4) NOT NULL,
    fast_average NUMERIC(18, 4),
    slow_average NUMERIC(18, 4),
    reason TEXT
);

CREATE INDEX IF NOT EXISTS idx_backtest_signal_task_id ON backtest_signal(task_id);

CREATE TABLE IF NOT EXISTS simulated_order (
    order_id VARCHAR(100) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES backtest_task(task_id) ON DELETE CASCADE,
    trade_date DATE NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL,
    price NUMERIC(18, 4) NOT NULL,
    quantity NUMERIC(18, 4) NOT NULL,
    amount NUMERIC(18, 4) NOT NULL,
    risk_reason TEXT
);

CREATE INDEX IF NOT EXISTS idx_simulated_order_task_id ON simulated_order(task_id);

CREATE TABLE IF NOT EXISTS equity_curve_point (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES backtest_task(task_id) ON DELETE CASCADE,
    trade_date DATE NOT NULL,
    cash NUMERIC(18, 4) NOT NULL,
    position_quantity NUMERIC(18, 4) NOT NULL,
    close_price NUMERIC(18, 4) NOT NULL,
    total_equity NUMERIC(18, 4) NOT NULL,
    drawdown NUMERIC(18, 4) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_equity_curve_point_task_id ON equity_curve_point(task_id);
