package com.pauleternity.marketdaily.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 日报 JSON 文档，生成结果和当时的证据一起保存。 */
record ReportContent(Analysis analysis, Input input) {

    /** 三个市场的分析、后续观察项及原文引用 ID。 */
    record Analysis(String summary, List<Section> sections) {
    }

    /** 市场代码固定，newsIds 只允许引用该市场的输入新闻。 */
    record Section(String market, String analysis, String watch, List<Long> newsIds) {
    }

    /** 同一业务日发送给模型的行情和精选新闻。 */
    record Input(LocalDate date, List<Price> prices, List<Article> news) {
    }

    /** 按来源保留价格、实际交易日和涨跌口径，缺失行情以空值表达。 */
    record Price(String symbol, String name, String market, String unit, String source, String comparisonType,
                 String freshness, String collectionStatus, BigDecimal price, BigDecimal changePercent,
                 LocalDate marketDate, Instant observedAt, Instant collectedAt) {
    }

    /** 每个去重事件的代表性文章，ID 用于模型引用，原文 URL 用于回看。 */
    record Article(long id, String title, String url, String source, String summary,
                   Instant publishedAt, Instant seenAt, List<String> markets) {
    }
}
