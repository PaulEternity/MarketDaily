-- 清理新闻时由数据库同步删除市场关系，避免留下孤立记录。
ALTER TABLE news_market_relation
    DROP FOREIGN KEY fk_news_market_news,
    ADD CONSTRAINT fk_news_market_news_cascade FOREIGN KEY (news_id) REFERENCES market_news (id) ON DELETE CASCADE;
