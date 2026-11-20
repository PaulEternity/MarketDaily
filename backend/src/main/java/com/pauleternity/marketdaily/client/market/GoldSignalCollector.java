package com.pauleternity.marketdaily.client.market;

import com.pauleternity.marketdaily.mapper.GoldSignalMapper;
import com.pauleternity.marketdaily.model.market.GoldSignals.Bar;
import com.pauleternity.marketdaily.model.market.GoldSignals.Period;
import com.pauleternity.marketdaily.model.market.GoldSignals.Symbol;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import com.pauleternity.marketdaily.service.market.GoldSignalService;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 获取公开国内日线和可选的 Gold API 国际现货 K 线。 */
@Component
class GoldSignalCollector {

    // Gold API 密钥为空时继续提供导入和查询，跳过外部 OHLC 请求。
    private static final String GOLD_API = "Gold API";
    private static final Logger log = LoggerFactory.getLogger(GoldSignalCollector.class);
    private final SourceHttp http;
    private final GoldSignalMapper bars;
    private final GoldSignalService signals;
    private final String goldApiKey;

    /** 复用现有行情 HTTP 客户端，以及集中式 K 线校验。 */
    GoldSignalCollector(SourceHttp http, GoldSignalMapper bars, GoldSignalService signals,
                        @Value("${GOLD_OHLC_API_KEY:}") String goldApiKey) {
        this.http = http;
        this.bars = bars;
        this.signals = signals;
        this.goldApiKey = goldApiKey.strip();
    }

    /** 北京时间每日上金所收盘后补齐日 OHLC 及日成交量。 */
    @Scheduled(cron = "${GOLD_SGE_DAILY_CRON:0 40 8,18 * * *}", zone = "Asia/Shanghai")
    void collectDomestic() {
        try {
            Instant now = Instant.now();
            var local = now.atZone(MarketAsset.REPORT_ZONE);
            // 日行情可能尚未发布，最多读取已过收盘时间的交易日。
            var through = local.toLocalTime().isBefore(LocalTime.of(15, 30))
                    ? local.toLocalDate().minusDays(1) : local.toLocalDate();
            String html = http.readBody(http.get().uri(
                    "https://www.sge.com.cn/sjzx/quotation_daily_new?start_date={from}&end_date={to}&inst_ids=Au99.99",
                    through.minusDays(30), through));
            var daily = SgeDailyTable.goldDailyBars(html, GoldSignalService.SGE_DAILY_SOURCE, through);
            if (!daily.isEmpty()) signals.save(daily, now);
            else log.warn("Au99.99 日行情未返回有效记录");
        } catch (RuntimeException failure) {
            log.warn("Au99.99 日行情采集失败：{}", failure.getClass().getSimpleName());
        }
    }

    /** 每小时补最近 3 根小时线和最近 5 个 UTC 日历日内的已完成日线。 */
    @Scheduled(cron = "${GOLD_OHLC_CRON:0 12 * * * *}", zone = "UTC")
    void collectSpot() {
        if (goldApiKey.isEmpty()) return;
        Instant now = Instant.now();
        try {
            collectSpotRange(Period.HOUR, now.truncatedTo(ChronoUnit.HOURS), Duration.ofHours(1), 3, now);
            collectSpotRange(Period.DAY, now.truncatedTo(ChronoUnit.DAYS), Duration.ofDays(1), 5, now);
        } catch (RuntimeException failure) {
            // 限流、凭证错误和请求失败交给下个调度周期，日志不含上游正文或密钥。
            log.warn("XAUUSD K 线采集中止：{}", failure.getClass().getSimpleName());
        }
    }

    /** 只查询库中当前缺失的区间，限制每轮请求数以保留 API 配额。 */
    private void collectSpotRange(Period period, Instant through, Duration step, int count, Instant now) {
        Instant from = through.minus(step.multipliedBy(count));
        // 仅跳过本来源已完整采集的桶，其他数据源的记录不会影响补采。
        Set<Instant> present = bars.range(Symbol.XAUUSD, period, from, through).stream()
                .filter(bar -> GOLD_API.equals(bar.source())).map(Bar::startTime).collect(Collectors.toSet());
        for (Instant start = from; start.isBefore(through); start = start.plus(step)) {
            var date = start.atOffset(ZoneOffset.UTC).toLocalDate();
            // 周六 UTC 无常规国际金交易；UTC 周日仅可能有尾段报价，不作为完整日线参照。
            if (present.contains(start) || date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || (period == Period.DAY && date.getDayOfWeek() == DayOfWeek.SUNDAY)) continue;
            // 结束秒取闭区间最后一秒，避免把下一根 K 线开盘价算进当前桶。
            var response = http.read(http.get().uri(
                    "https://api.gold-api.com/ohlc/XAU?startTimestamp={start}&endTimestamp={end}",
                    start.getEpochSecond(), start.plus(step).minusSeconds(1).getEpochSecond())
                    .header("x-api-key", goldApiKey));
            var open = quote(response.path("open"));
            var high = quote(response.path("high"));
            var low = quote(response.path("low"));
            var close = quote(response.path("close"));
            if (open == null || high == null || low == null || close == null) continue;
            signals.save(List.of(new Bar(Symbol.XAUUSD, period, GOLD_API, start, date,
                    open, high, low, close, null, null, null)), now);
        }
    }

    /** 将来源超出数据库小数位的尾数按统一精度取整。 */
    private static BigDecimal quote(com.fasterxml.jackson.databind.JsonNode value) {
        BigDecimal price = SourceHttp.decimal(value);
        return price == null || price.signum() <= 0 ? null : price.setScale(8, RoundingMode.HALF_UP);
    }
}
