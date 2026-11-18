CREATE TABLE market_price (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    asset_symbol VARCHAR(20) NOT NULL,
    asset_name VARCHAR(100) NOT NULL,
    price DECIMAL(24, 8),
    previous_price DECIMAL(24, 8),
    change_amount DECIMAL(24, 8),
    change_percent DECIMAL(12, 6),
    currency VARCHAR(10) NOT NULL,
    comparison_type VARCHAR(40) NOT NULL,
    market_date DATE,
    observed_at DATETIME(3),
    collected_at DATETIME(3) NOT NULL,
    report_date DATE NOT NULL,
    source VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    CONSTRAINT uq_market_price_snapshot UNIQUE (asset_symbol, report_date, source)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE market_news (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    title_normalized VARCHAR(512) NOT NULL,
    url TEXT NOT NULL,
    url_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(255) NOT NULL,
    language VARCHAR(35),
    short_description TEXT,
    published_at DATETIME(3),
    collected_at DATETIME(3) NOT NULL,
    event_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    importance_score DECIMAL(5, 2) NOT NULL,
    is_selected BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_market_news_url_hash UNIQUE (url_hash),
    INDEX ix_market_news_published_at (published_at),
    INDEX ix_market_news_event_hash (event_hash)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE news_market_relation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    news_id BIGINT NOT NULL,
    market_type ENUM('GOLD', 'NASDAQ', 'CRYPTO') NOT NULL,
    CONSTRAINT uq_news_market_relation UNIQUE (news_id, market_type),
    CONSTRAINT fk_news_market_news FOREIGN KEY (news_id) REFERENCES market_news (id),
    INDEX ix_news_market_type (market_type, news_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE daily_report (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_date DATE NOT NULL,
    overall_summary TEXT NOT NULL,
    report_content JSON NOT NULL,
    input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    input_tokens INT UNSIGNED,
    output_tokens INT UNSIGNED,
    estimated_cost DECIMAL(16, 8),
    status VARCHAR(20) NOT NULL,
    generated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_daily_report_date UNIQUE (report_date)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE task_execution (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_type VARCHAR(40) NOT NULL,
    business_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_no INT UNSIGNED NOT NULL,
    started_at DATETIME(3),
    finished_at DATETIME(3),
    error_message TEXT,
    trace_id VARCHAR(64) NOT NULL,
    CONSTRAINT uq_task_execution_attempt UNIQUE (task_type, business_date, attempt_no),
    INDEX ix_task_execution_trace_id (trace_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_term (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    term VARCHAR(100) NOT NULL,
    explanation TEXT NOT NULL,
    category VARCHAR(40) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_knowledge_term UNIQUE (term)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;