package com.pauleternity.marketdaily.model.news;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/** 入库前的清洗结果。newsAt 是窗口与排序使用的实际出版或来源收录时间。 */
public record NewsArticle(String title, String titleNormalized, String url, String urlHash, String source, String language,
                   String summary, Instant publishedAt, Instant seenAt, Instant newsAt, Instant collectedAt,
                   String eventHash, BigDecimal score, Set<NewsMarket> markets) {
}
