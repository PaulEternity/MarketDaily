package com.pauleternity.marketdaily.model.market;

import java.time.Instant;
import java.util.List;

/** 小时趋势的 UTC 时间窗口及已采集报价。 */
public record HourlyMarketHistory(String unit, Instant from, Instant through, List<HourlyQuote> points,
                                  // 国内金银计划在常规休市采样的 UTC 整点，仅用于绘图。
                                  List<Instant> closedSamplingHours) {
}
