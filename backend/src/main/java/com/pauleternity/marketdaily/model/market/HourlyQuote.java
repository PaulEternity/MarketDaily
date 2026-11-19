package com.pauleternity.marketdaily.model.market;

import java.math.BigDecimal;
import java.time.Instant;

/** 小时桶的起始时刻和该小时内最新的有效价格。 */
public record HourlyQuote(Instant hour, BigDecimal price) {
}
