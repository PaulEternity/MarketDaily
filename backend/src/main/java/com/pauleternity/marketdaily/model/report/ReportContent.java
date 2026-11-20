package com.pauleternity.marketdaily.model.report;

import com.pauleternity.marketdaily.model.market.GoldSignals.Breakouts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 日报 JSON 文档，生成结果和当时的证据一起保存。 */
public record ReportContent(Analysis analysis, Input input) {

    /** 三个市场的分析、后续观察项及原文引用 ID。 */
    public record Analysis(String summary, List<Section> sections) {
    }

    /** 市场代码固定，newsIds 只允许引用该市场的输入新闻。 */
    public record Section(String market, String analysis, String watch, List<Long> newsIds) {
    }

    /** 同一业务日发送给模型的行情和精选新闻。 */
    public record Input(LocalDate date, List<Price> prices, List<Article> news, List<GoldEvidence> goldSignals) {
        /** 兼容没有黄金统计的历史快照和调用方。 */
        public Input {
            goldSignals = goldSignals == null ? List.of() : goldSignals;
        }

        /** 构造未包含黄金统计的输入。 */
        public Input(LocalDate date, List<Price> prices, List<Article> news) {
            this(date, prices, news, List.of());
        }
    }

    /** 给日报的已计算事实，保留来源、统计样本数和成交量时间口径。 */
    public record GoldEvidence(String symbol, String hourlySource, int availableHours, Breakouts breakouts,
                               LatestVolume latestVolume) {
    }

    /** 单条量价证据，国内日量按双向千克，COMEX 小时量按单向手数。 */
    public record LatestVolume(String source, String period, String unit, LocalDate tradingDate, Instant startTime,
                               BigDecimal volume, BigDecimal buyVolume, BigDecimal sellVolume) {
    }

    /** 按来源保留价格、实际交易日和涨跌口径，缺失行情以空值表达。 */
    public record Price(String symbol, String name, String market, String unit, String source, String comparisonType,
                 String freshness, String collectionStatus, BigDecimal price, BigDecimal changePercent,
                 LocalDate marketDate, Instant observedAt, Instant collectedAt) {
    }

    /** 每个去重事件的代表性文章，ID 用于模型引用，原文 URL 用于回看。 */
    public record Article(long id, String title, String url, String source, String summary,
                   Instant publishedAt, Instant seenAt, List<String> markets) {
    }
}
