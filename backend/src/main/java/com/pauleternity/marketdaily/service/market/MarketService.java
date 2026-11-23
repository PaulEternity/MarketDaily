package com.pauleternity.marketdaily.service.market;

import com.pauleternity.marketdaily.client.market.MarketQuoteProvider;
import com.pauleternity.marketdaily.client.market.MarketQuoteProvider.Quote;
import com.pauleternity.marketdaily.client.market.SgeTradingHours;
import com.pauleternity.marketdaily.mapper.MarketPriceMapper;
import com.pauleternity.marketdaily.model.market.HourlyMarketHistory;
import com.pauleternity.marketdaily.model.market.LatestQuote;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import com.pauleternity.marketdaily.model.market.MarketHistory;
import com.pauleternity.marketdaily.model.market.MarketSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** 执行行情采集、比较和查询，隔离来源失败。 */
@Service
public class MarketService {

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);
    private final MarketPriceMapper prices;
    private final List<MarketQuoteProvider> providers;
    // 与小时采集器使用相同的计划。禁用定时采样时为空，所有缺报照常留空。
    private final CronExpression hourlySchedule;

    /** 通过 Provider 列表接入行情来源，通过 Mapper 操作日度快照。 */
    MarketService(MarketPriceMapper prices, List<MarketQuoteProvider> providers,
                  @Value("${MARKET_HOURLY_CRON:0 5 * * * *}") String hourlyCron) {
        this.prices = prices;
        this.providers = providers;
        this.hourlySchedule = Scheduled.CRON_DISABLED.equals(hourlyCron) ? null : CronExpression.parse(hourlyCron);
    }

    /** 同一实例串行采集，整轮按触发时的业务日期入库。 */
    public synchronized List<LatestQuote> collect(LocalDate day) {
        collectMatching(day, asset -> true);
        return latest(day);
    }

    /** 每小时只采集支持盘中报价的来源，和手动采集共用同一把锁。 */
    @Scheduled(cron = "${MARKET_HOURLY_CRON:0 5 * * * *}", zone = "Asia/Shanghai")
    synchronized void collectHourly() {
        collectMatching(LocalDate.now(MarketAsset.REPORT_ZONE), MarketAsset::hasTimedQuote);
    }

    /** 上午更新纳指最近收盘，傍晚更新上金所收盘，并重查纳指发布结果。 */
    @Scheduled(cron = "${MARKET_DAILY_CRON:0 30 8,18 * * *}", zone = "Asia/Shanghai")
    synchronized void collectDaily() {
        collectMatching(LocalDate.now(MarketAsset.REPORT_ZONE), asset -> !asset.hasTimedQuote());
    }

    /** 手动和定时任务复用采集流程，按本轮目标筛选资产。 */
    private void collectMatching(LocalDate day, Predicate<MarketAsset> included) {
        for (MarketQuoteProvider provider : providers) {
            // 此轮需要请求并保存的资产范围，手动采集涵盖全部来源。
            var assets = provider.assets();
            if (assets.stream().noneMatch(included)) {
                continue;
            }
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
            for (MarketAsset asset : assets) {
                if (included.test(asset)) {
                    save(asset, quotes.get(asset), day);
                }
            }
        }
    }

    /** 按固定资产顺序返回最新报价、数据新旧及当天的采集状态。 */
    public List<LatestQuote> latest(LocalDate day) {
        return MarketAsset.ACTIVE.stream().map(asset -> {
            MarketSnapshot today = prices.onDate(asset.name(), asset.source(), day);
            MarketSnapshot quote = today != null && today.price() != null ? today
                    : prices.latestBefore(asset.name(), asset.source(), day);
            String freshness = freshness(asset, quote, day);
            return new LatestQuote(asset.name(), asset.displayName(), asset.unit(), asset.source(), asset.comparisonType(),
                    freshness, today == null ? "NOT_COLLECTED" : today.status(), quote);
        }).toList();
    }

    /** 收盘价按最近交易日展示，日度采样和滚动报价则标记跨日旧值。 */
    private String freshness(MarketAsset asset, MarketSnapshot quote, LocalDate day) {
        if (quote == null) {
            return "MISSING";
        }
        boolean sameReportDay = day.equals(quote.reportDate());
        boolean currentMarketDate = "PREVIOUS_TRADING_CLOSE".equals(asset.comparisonType()) || day.equals(quote.marketDate());
        return sameReportDay && currentMarketDate && "AVAILABLE".equals(quote.status()) ? "CURRENT" : "STALE";
    }

    /** 返回 7 或 30 个自然日的窗口，按真实行情日期去重，保留没有行情的日期缺口。 */
    public MarketHistory history(MarketAsset asset, int days, LocalDate day) {
        LocalDate from = day.minusDays(days - 1L);
        // 同一实际行情日期可能被多次采到，查询已把最新的快照放在前面。
        Map<LocalDate, MarketSnapshot> byMarketDate = new LinkedHashMap<>();
        for (MarketSnapshot quote : prices.history(asset.name(), asset.source(), from, day)) {
            byMarketDate.putIfAbsent(quote.marketDate(), quote);
        }
        return new MarketHistory(asset.name(), asset.unit(), from, day, List.copyOf(byMarketDate.values()));
    }

    /** 返回最近 24 小时的真实报价和国内金银的常规休市采样小时。 */
    public HourlyMarketHistory hourlyHistory(MarketAsset asset, Instant now) {
        // 查询边界对齐整点，过去 23 小时加当前小时正好是 24 个桶。
        Instant through = now.truncatedTo(ChronoUnit.HOURS);
        Instant from = through.minus(23, ChronoUnit.HOURS);
        // 仅上金所延时报价共用这套盘时，其余资产的缺报无休市分类。
        boolean domesticMetal = asset == MarketAsset.AU9999 || asset == MarketAsset.AGTD;
        List<Instant> closedHours = domesticMetal && hourlySchedule != null
                ? SgeTradingHours.closedSamplingHours(hourlySchedule, from, through) : List.of();
        return new HourlyMarketHistory(asset.unit(), from, through,
                prices.hourlyHistory(asset.name(), asset.source(), from, through), closedHours);
    }

    /** 保存单一资产的结果，失败或者缺少比较值时使用空值。 */
    private void save(MarketAsset asset, Quote quote, LocalDate day) {
        // 按来源时间归桶，避免把休市时返回的旧报价填到当前小时。
        if (asset.hasTimedQuote() && quote != null && quote.observedAt() != null) {
            Instant observedAt = quote.observedAt();
            prices.saveHourly(asset.name(), asset.source(), observedAt.truncatedTo(ChronoUnit.HOURS),
                    observedAt, quote.price());
        }
        // 国际金银的上一可比值来自本地，交易日收盘及滚动涨跌以各来源数据为准。
        BigDecimal previous = quote == null ? null : quote.previousPrice();
        BigDecimal change = quote == null ? null : quote.changeAmount();
        BigDecimal percent = quote == null ? null : quote.changePercent();
        if (quote != null && "PREVIOUS_DAILY_SAMPLE".equals(asset.comparisonType()) && day.equals(quote.marketDate())) {
            MarketSnapshot earlier = prices.previousDailySample(asset.name(), asset.source(), day);
            if (earlier != null && earlier.observedAt() != null && earlier.price().signum() > 0
                    && earlier.observedAt().isBefore(quote.observedAt())) {
                previous = earlier.price();
            }
        }
        if (quote != null && previous != null && !"ROLLING_24_HOURS".equals(asset.comparisonType())) {
            change = quote.price().subtract(previous);
            percent = change.movePointRight(2).divide(previous, 6, RoundingMode.HALF_UP);
        }
        if (quote == null) {
            log.warn("本轮未取得有效行情，资产={}", asset);
        }
        prices.save(new MarketSnapshot(asset.name(), asset.displayName(), quote == null ? null : quote.price(),
                previous, change, percent, asset.currency(), asset.comparisonType(),
                quote == null ? null : quote.marketDate(), quote == null ? null : quote.observedAt(),
                Instant.now(), day, asset.source(), quote == null ? "FAILED" : "AVAILABLE"));
    }
}
