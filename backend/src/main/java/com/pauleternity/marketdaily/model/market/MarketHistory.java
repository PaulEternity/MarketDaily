package com.pauleternity.marketdaily.model.market;

import java.time.LocalDate;
import java.util.List;

/** 历史走势数据和窗口边界，points 按实际行情日期升序。 */
public record MarketHistory(String symbol, String unit, LocalDate from, LocalDate through, List<MarketSnapshot> points) {
}
