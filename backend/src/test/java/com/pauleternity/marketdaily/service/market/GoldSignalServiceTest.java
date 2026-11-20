package com.pauleternity.marketdaily.service.market;

import com.pauleternity.marketdaily.model.market.GoldSignals.Bar;
import com.pauleternity.marketdaily.model.market.GoldSignals.Breakouts;
import com.pauleternity.marketdaily.model.market.GoldSignals.Period;
import com.pauleternity.marketdaily.model.market.GoldSignals.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证突破统计跨休市日、区分影线与收盘，并排除无参照数据。 */
class GoldSignalServiceTest {

    /** 夜盘按交易归属取上一日，晚到的未来日线不能作为参照。 */
    @Test
    void countsOnlyHoursComparedWithAPreviousTradingDay() {
        // 周五夜盘归属周一，应该比较已经结束的周五白天日线。
        var hourly = List.of(
                bar(Period.HOUR, "2026-11-19T12:00:00Z", "2026-11-20", 130, 70, 110),
                bar(Period.HOUR, "2026-11-20T12:00:00Z", "2026-11-23", 121, 95, 110),
                bar(Period.HOUR, "2026-11-20T13:00:00Z", "2026-11-23", 120, 85, 86));
        var daily = List.of(
                bar(Period.DAY, "2026-11-20T00:00:00Z", "2026-11-20", 120, 90, 110),
                bar(Period.DAY, "2026-11-23T00:00:00Z", "2026-11-23", 140, 75, 110));

        assertThat(GoldSignalService.summarize(hourly, daily)).isEqualTo(new Breakouts(2, 1, 0, 1, 1));
    }

    /** 为测试构造同一来源的国内合约 K 线。 */
    private static Bar bar(Period period, String start, String day, int high, int low, int close) {
        return new Bar(Symbol.AU9999, period, GoldSignalService.SGE_DAILY_SOURCE,
                Instant.parse(start), LocalDate.parse(day), BigDecimal.valueOf(100), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), null, null, null);
    }
}
