-- 每个资产和来源在一个 UTC 小时内保留时间最新的有效报价。
CREATE TABLE market_price_hourly (
    asset_symbol VARCHAR(20) NOT NULL,
    source VARCHAR(100) NOT NULL,
    hour_start DATETIME(3) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    price DECIMAL(24, 8) NOT NULL,
    PRIMARY KEY (asset_symbol, source, hour_start)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
