package com.pauleternity.marketdaily.client.market;

import com.pauleternity.marketdaily.model.market.MarketAsset;
import org.springframework.scheduling.support.CronExpression;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** 上金所采集器和趋势查询共用的北京时间常规盘时。 */
public final class SgeTradingHours {

    /** 盘时计算只依赖传入的时间和采样计划。 */
    private SgeTradingHours() {
    }

    /** 跨午夜的夜盘按开盘日期判断，周六凌晨可以属于周五夜盘。 */
    static LocalDate sessionDate(ZonedDateTime local) {
        return local.toLocalTime().isBefore(LocalTime.of(2, 30))
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    /** 判断北京时间是否处于常规日盘或夜盘。 */
    static boolean inSession(ZonedDateTime local, LocalDate sessionDate) {
        if (sessionDate.getDayOfWeek().getValue() > DayOfWeek.FRIDAY.getValue()) {
            return false;
        }
        var time = local.toLocalTime();
        return time.isBefore(LocalTime.of(2, 30)) || !time.isBefore(LocalTime.of(20, 0))
                || (!time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(11, 30)))
                || (!time.isBefore(LocalTime.of(13, 30)) && time.isBefore(LocalTime.of(15, 30)));
    }

    /** 返回有计划采样且全部采样时刻落在常规休市内的 UTC 小时桶。 */
    public static List<Instant> closedSamplingHours(CronExpression schedule, Instant from, Instant through) {
        // 记录可作为虚线候选的整点。节假日和临时停市按常规盘时保守判断。
        List<Instant> closedHours = new ArrayList<>();
        for (var hour = from.atZone(MarketAsset.REPORT_ZONE); !hour.toInstant().isAfter(through);
                hour = hour.plusHours(1)) {
            // 常规盘时只在整点和半点切换，按两个半小时分别检查计划采样。
            var half = hour.plusMinutes(30);
            var nextHour = hour.plusHours(1);
            boolean firstHalfSample = hasSample(schedule, hour, half);
            boolean secondHalfSample = hasSample(schedule, half, nextHour);
            boolean hasOpenSample = (firstHalfSample && inSession(hour, sessionDate(hour)))
                    || (secondHalfSample && inSession(half, sessionDate(half)));
            if ((firstHalfSample || secondHalfSample) && !hasOpenSample) {
                closedHours.add(hour.toInstant());
            }
        }
        return List.copyOf(closedHours);
    }

    /** 查询半开时间区间内是否有采样计划，包含区间起点。 */
    private static boolean hasSample(CronExpression schedule, ZonedDateTime start, ZonedDateTime end) {
        var trigger = schedule.next(start.minusSeconds(1));
        return trigger != null && trigger.isBefore(end);
    }
}
