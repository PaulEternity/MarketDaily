package com.pauleternity.marketdaily.service.report;

import com.pauleternity.marketdaily.mapper.NewsMapper;
import com.pauleternity.marketdaily.model.market.GoldSignals;
import com.pauleternity.marketdaily.model.market.LatestQuote;
import com.pauleternity.marketdaily.model.news.NewsMarket;
import com.pauleternity.marketdaily.model.news.NewsRow;
import com.pauleternity.marketdaily.model.report.ReportContent;
import com.pauleternity.marketdaily.service.market.GoldSignalService;
import com.pauleternity.marketdaily.service.market.MarketService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** 先在本地整理真实证据，同一事件只给模型一篇代表文章。 */
@Component
class ReportInput {

    // 单份日报最多引用八条新闻，先保障各市场都能选到候选。
    private static final int MAX_NEWS = 8;
    private final MarketService market;
    private final NewsMapper news;
    private final GoldSignalService gold;

    /** 复用已有行情降级与新闻评分查询。 */
    ReportInput(MarketService market, NewsMapper news, GoldSignalService gold) {
        this.market = market;
        this.news = news;
        this.gold = gold;
    }

    /** 读取当日报价及最近 24 小时新闻，不请求外部行情或新闻源。 */
    ReportContent.Input snapshot(LocalDate date, Instant through) {
        var prices = market.latest(date).stream().map(this::price).toList();
        Instant from = through.minus(Duration.ofHours(24));
        // 键为事件哈希，插入顺序保留代表条目的优先级。
        var selected = new LinkedHashMap<String, NewsRow>();
        for (NewsMarket category : NewsMarket.values()) {
            for (var row : news.page(from, through, category, MAX_NEWS, 0)) {
                if (selected.putIfAbsent(row.eventHash(), row) == null) {
                    break;
                }
            }
        }
        for (var row : news.page(from, through, null, 50, 0)) {
            if (selected.size() == MAX_NEWS) {
                break;
            }
            selected.putIfAbsent(row.eventHash(), row);
        }
        // 附上同一截止时刻可查的黄金统计，不传整段 K 线给模型。
        var goldEvidence = gold.overview(through).stream().map(this::goldEvidence).toList();
        return new ReportContent.Input(date, prices, selected.values().stream().map(this::article).toList(), goldEvidence);
    }

    /** 优先引用最近的小时成交量，国内没有小时量时保留交易所日成交量。 */
    private ReportContent.GoldEvidence goldEvidence(GoldSignals signal) {
        var bar = signal.hourly().reversed().stream().filter(item -> item.volume() != null).findFirst()
                .orElseGet(() -> signal.daily().reversed().stream().filter(item -> item.volume() != null).findFirst().orElse(null));
        // 日量与分时量口径在快照中独立描述，防止被误作同期成交。
        var unit = bar != null && bar.symbol() == GoldSignals.Symbol.AU9999
                ? signal.volumeUnit() + (bar.period() == GoldSignals.Period.DAY ? "（双向）" : "（单向）")
                : signal.volumeUnit();
        var volume = bar == null ? null : new ReportContent.LatestVolume(bar.source(), bar.period().name(), unit,
                bar.tradingDate(), bar.startTime(), bar.volume(), bar.buyVolume(), bar.sellVolume());
        return new ReportContent.GoldEvidence(signal.symbol().name(),
                signal.hourly().isEmpty() ? null : signal.hourly().getLast().source(),
                signal.hourly().size(), signal.breakouts(), volume);
    }

    /** 保留页面相同的报价状态和比较口径，不能把旧数据当成当日报价。 */
    private ReportContent.Price price(LatestQuote item) {
        var quote = item.quote();
        String category = switch (item.symbol()) {
            case "NASDAQCOM" -> "NASDAQ";
            case "BTCUSD" -> "CRYPTO";
            default -> "GOLD";
        };
        return new ReportContent.Price(item.symbol(), item.name(), category, item.unit(), item.source(),
                item.comparisonType(), item.freshness(), item.collectionStatus(), quote == null ? null : quote.price(),
                quote == null ? null : quote.changePercent(), quote == null ? null : quote.marketDate(),
                quote == null ? null : quote.observedAt(), quote == null ? null : quote.collectedAt());
    }

    /** 限制标题和摘要长度，原文链接单独保留用于追溯。 */
    private ReportContent.Article article(NewsRow row) {
        List<String> markets = row.markets() == null ? List.of() : Arrays.asList(row.markets().split(","));
        return new ReportContent.Article(row.id(), truncate(row.title(), 180), row.url(), row.source(),
                truncate(row.summary(), 240), row.publishedAt(), row.seenAt(), markets);
    }

    /** 以 Unicode 字符数截断，避免切断表情或扩展汉字。 */
    private String truncate(String value, int limit) {
        return value == null || value.codePointCount(0, value.length()) <= limit
                ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }
}
