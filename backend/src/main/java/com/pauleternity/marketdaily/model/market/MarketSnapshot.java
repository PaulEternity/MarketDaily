package com.pauleternity.marketdaily.model.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 行情日度快照。reportDate 是北京时间业务日期，marketDate 是实际行情日期。
 * observedAt 和 collectedAt 分别为来源时间和采集时间，以 UTC 保存。
 * status 为 AVAILABLE 或 FAILED，失败后可能保留同日上一次有效报价。
 */
public record MarketSnapshot(String symbol, String name, BigDecimal price, BigDecimal previousPrice,
                             BigDecimal changeAmount, BigDecimal changePercent, String currency,
                             String comparisonType, LocalDate marketDate, Instant observedAt,
                             Instant collectedAt, LocalDate reportDate, String source, String status) {
}
