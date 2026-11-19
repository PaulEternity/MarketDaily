-- GDELT 提供收录时间，不能写成新闻发布时间。
ALTER TABLE market_news
    ADD COLUMN source_seen_at DATETIME(3) NULL AFTER published_at,
    ADD COLUMN news_at DATETIME(3) GENERATED ALWAYS AS (COALESCE(published_at, source_seen_at)) STORED,
    ADD INDEX ix_market_news_news_at_id (news_at, id);
