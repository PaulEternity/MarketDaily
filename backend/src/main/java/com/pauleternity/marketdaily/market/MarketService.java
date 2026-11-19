package com.pauleternity.marketdaily.market;

import com.pauleternity.marketdaily.market.MarketQuoteProvider.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 执行行情采集、比较和查询，隔离来源失败。 */
@Service
class MarketService {

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);
    private final MarketPriceMapper prices;
    private final List<MarketQuoteProvider> providers;

    /** 通过 Provider 列表接入行情来源，通过 Mapper 操作日度快照。 */
    MarketService(MarketPriceMapper prices, List<MarketQuoteProvider> providers) {
        this.prices = prices;
        this.providers = providers;
    }

    /** 同一实例串行采集，整轮按触发时的业务日期入库。 */
    synchronized List<Latest> collect(LocalDate day) {
        for (MarketQuoteProvider provider : providers) {
            // 仅在此捕获第三方异常，数据库写入错误仍按系统错误处理。
            Map<MarketAsset, Quote> quotes;
            try {
                quotes = provider.fetch();
            } catch (RuntimeException failure) {
                // 外部异常消息可能包含 URL 和 API Key，只记录类型。
                log.warn("行情来源采集失败，资产={}，异常类型={}", provider.assets(),
                        failure.getClass().getSimpleName());
                quotes = Map.of();
            }
            for (MarketAsset asset : provider.assets()) {
                save(asset, quotes.get(asset), day);
            }
        }
        return latest(day);
    }

    /** 按固定资产顺序返回最新报价、数据新旧及当天的采集状态。 */
    List<Latest> latest(LocalDate day) {
        return Arrays.stream(MarketAsset.values()).map(asset -> {
            MarketSnapshot today = prices.onDate(asset.name(), asset.source, day);
            MarketSnapshot quote = today != null && today.price() != null ? today
                    : prices.latestBefore(asset.name(), asset.source, day);
            String freshness = freshness(asset, quote, day);
            return new Latest(asset.name(), asset.displayName, asset.unit, asset.source, asset.comparisonType,
                    freshness, today == null ? "NOT_COLLECTED" : today.status(), quote);
        }).toList();
    }

    /** FRED 展示最近可用的交易日，其他资产的跨日旧报价明确标记为过期。 */
    private String freshness(MarketAsset asset, MarketSnapshot quote, LocalDate day) {
        if (quote == null) {
            return "MISSING";
        }
        boolean sameReportDay = day.equals(quote.reportDate());
        boolean currentMarketDate = asset == MarketAsset.NASDAQCOM || day.equals(quote.marketDate());
        return sameReportDay && currentMarketDate && "AVAILABLE".equals(quote.status()) ? "CURRENT" : "STALE";
    }

    /** 返回 7 或 30 个自然日的窗口，按真实行情日期去重，保留没有行情的日期缺口。 */
    History history(MarketAsset asset, int days, LocalDate day) {
        LocalDate from = day.minusDays(days - 1L);
        // 同一实际行情日期可能被多次采到，查询已把最新的快照放在前面。
        Map<LocalDate, MarketSnapshot> byMarketDate = new LinkedHashMap<>();
        for (MarketSnapshot quote : prices.history(asset.name(), asset.source, from, day)) {
            byMarketDate.putIfAbsent(quote.marketDate(), quote);
        }
        return new History(asset.name(), asset.unit, from, day, List.copyOf(byMarketDate.values()));
    }

    /** 保存单一资产的结果，失败或者缺少比较值时使用空值。 */
    private void save(MarketAsset asset, Quote quote, LocalDate day) {
        // 只有黄金的上一可比值来自本地，另两类比较口径由来源定义。
        BigDecimal previous = quote == null ? null : quote.previousPrice();
        BigDecimal change = quote == null ? null : quote.changeAmount();
        BigDecimal percent = quote == null ? null : quote.changePercent();
        if (quote != null && asset == MarketAsset.XAUUSD && day.equals(quote.marketDate())) {
            MarketSnapshot earlier = prices.previousGoldSample(day);
            if (earlier != null && earlier.observedAt() != null && earlier.price().signum() > 0
                    && earlier.observedAt().isBefore(quote.observedAt())) {
                previous = earlier.price();
            }
        }
        if (quote != null && previous != null && asset != MarketAsset.BTCUSD && asset != MarketAsset.ETHUSD) {
            change = quote.price().subtract(previous);
            percent = change.movePointRight(2).divide(previous, 6, RoundingMode.HALF_UP);
        }
        if (quote == null) {
            log.warn("本轮未取得有效行情，资产={}", asset);
        }
        prices.save(new MarketSnapshot(asset.name(), asset.displayName, quote == null ? null : quote.price(),
                previous, change, percent, asset.currency, asset.comparisonType,
                quote == null ? null : quote.marketDate(), quote == null ? null : quote.observedAt(),
                Instant.now(), day, asset.source, quote == null ? "FAILED" : "AVAILABLE"));
    }

    /** 行情为空时仍暴露资产信息和当天采集状态，区分缺失和旧值。 */
    record Latest(String symbol, String name, String unit, String source, String comparisonType,
                  String freshness, String collectionStatus, MarketSnapshot quote) {
    }

    /** 历史走势数据和窗口边界，points 按实际行情日期升序。 */
    record History(String symbol, String unit, LocalDate from, LocalDate through, List<MarketSnapshot> points) {
    }
}
