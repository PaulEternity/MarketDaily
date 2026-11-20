-- 只存来源提供的已收盘 K 线，与既有小时报价快照分开。
CREATE TABLE gold_signal_bar (
    symbol VARCHAR(12) NOT NULL,
    period VARCHAR(4) NOT NULL,
    source VARCHAR(100) NOT NULL,
    start_time DATETIME NOT NULL,
    trading_date DATE NOT NULL,
    open_price DECIMAL(24, 8) NOT NULL,
    high_price DECIMAL(24, 8) NOT NULL,
    low_price DECIMAL(24, 8) NOT NULL,
    close_price DECIMAL(24, 8) NOT NULL,
    volume DECIMAL(28, 8),
    buy_volume DECIMAL(28, 8),
    sell_volume DECIMAL(28, 8),
    PRIMARY KEY (symbol, period, start_time, source)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
