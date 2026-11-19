package com.pauleternity.marketdaily.model.news;

import java.time.Instant;
import java.util.List;

/** 从 1 开始的页码、查询窗口和新闻列表。 */
public record NewsPage(Instant from, Instant through, int page, int size, long total, List<NewsItem> items) {
}
