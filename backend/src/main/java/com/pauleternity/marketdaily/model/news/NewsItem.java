package com.pauleternity.marketdaily.model.news;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 列表的来源信息，selected 将由后续 AI 模块维护。 */
public record NewsItem(long id, String title, String url, String source, String language, String summary,
            Instant publishedAt, Instant seenAt, Instant collectedAt, String eventHash,
            BigDecimal score, boolean selected, List<NewsMarket> markets) {
}
