package com.pauleternity.marketdaily.model.news;

import java.math.BigDecimal;
import java.time.Instant;

/** 查询视图列顺序对应构造器，markets 用逗号分隔的事件市场枚举值表示。 */
public record NewsRow(long id, String title, String url, String source, String language, String summary,
           Instant publishedAt, Instant seenAt, Instant collectedAt, String eventHash,
           BigDecimal score, boolean selected, String markets) {
}
