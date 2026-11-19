package com.pauleternity.marketdaily.client.market;

import com.pauleternity.marketdaily.mapper.MarketPriceMapper;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;

/** 将上金所延时最新价按采集时刻归档，独立于每日收盘采集。 */
@Component
class SgeDelayedSampler {

    private static final Logger log = LoggerFactory.getLogger(SgeDelayedSampler.class);
    // 页面的报告日期只有年月日，用于过滤跨交易日的旧页面。
    private static final Pattern PAGE_DATE =
            Pattern.compile("上海黄金交易所(\\d{4})年(\\d{1,2})月(\\d{1,2})日延时行情");
    private final SourceHttp http;
    private final MarketPriceMapper prices;

    /** 注入行情 HTTP 客户端和小时报价表的 Mapper。 */
    SgeDelayedSampler(SourceHttp http, MarketPriceMapper prices) {
        this.http = http;
        this.prices = prices;
    }

    /** 在正常交易时段内，每小时检查一次国内金银的延时行情。 */
    @Scheduled(cron = "${MARKET_HOURLY_CRON:0 5 * * * *}", zone = "Asia/Shanghai")
    void collect() {
        sample(Instant.now());
    }

    /** 校验交易时段、页面日期和报价后，按采集小时写入已有的小时表。 */
    void sample(Instant sampledAt) {
        // 北京时间用于交易时段，UTC 时刻用于归档和跨时区查询。
        var local = sampledAt.atZone(MarketAsset.REPORT_ZONE);
        // 夜盘以开盘所在日判断；周六凌晨可以属于周五夜盘。
        var sessionDate = local.toLocalTime().isBefore(LocalTime.of(2, 30))
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
        if (!inSession(local, sessionDate)) {
            return;
        }

        Map<String, BigDecimal> quotes;
        try {
            String html = http.readBody(http.get().uri("https://www.sge.com.cn/sjzx/yshqbg"));
            if (!matchesReportDate(html, local, sessionDate)) {
                return;
            }
            quotes = SgeDailyTable.delayedPrices(html, SgeQuoteProvider.CONTRACTS.keySet());
        } catch (RuntimeException failure) {
            log.warn("国内金银延时行情采集失败，异常类型={}", failure.getClass().getSimpleName());
            return;
        }

        // 延时页不提供成交时间。此处保存采集时间，趋势图显式标明时间口径。
        var hour = sampledAt.truncatedTo(ChronoUnit.HOURS);
        quotes.forEach((contract, price) -> {
            var asset = SgeQuoteProvider.CONTRACTS.get(contract);
            prices.saveHourly(asset.name(), asset.source(), hour, sampledAt, price);
        });
    }

    /** 判断北京时间是否落在上金所常规日盘或夜盘的交易时段。 */
    private boolean inSession(ZonedDateTime local, LocalDate sessionDate) {
        if (sessionDate.getDayOfWeek().getValue() > DayOfWeek.FRIDAY.getValue()) {
            return false;
        }
        var time = local.toLocalTime();
        return time.isBefore(LocalTime.of(2, 30)) || !time.isBefore(LocalTime.of(20, 0))
                || (!time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(11, 30)))
                || (!time.isBefore(LocalTime.of(13, 30)) && time.isBefore(LocalTime.of(15, 30)));
    }

    /** 日盘要求同日页面；夜盘兼容开盘日和归属的下一交易日期。 */
    private boolean matchesReportDate(String html, ZonedDateTime local, LocalDate sessionDate) {
        var date = PAGE_DATE.matcher(html);
        if (!date.find()) {
            return false;
        }
        var reported = LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)),
                Integer.parseInt(date.group(3)));
        var time = local.toLocalTime();
        if (!time.isBefore(LocalTime.of(20, 0)) || time.isBefore(LocalTime.of(2, 30))) {
            var nextWeekday = sessionDate.plusDays(sessionDate.getDayOfWeek() == DayOfWeek.FRIDAY ? 3 : 1);
            return reported.equals(sessionDate) || reported.equals(nextWeekday);
        }
        return reported.equals(local.toLocalDate());
    }
}
