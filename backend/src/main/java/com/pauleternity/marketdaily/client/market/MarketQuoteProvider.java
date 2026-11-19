package com.pauleternity.marketdaily.client.market;

import com.pauleternity.marketdaily.model.market.MarketAsset;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** 外部行情源的统一契约，缺少某个资产表示该资产本轮未成功。 */
public interface MarketQuoteProvider {

    /** 返回该来源负责的全部资产，用于逐资产记录本轮结果。 */
    Set<MarketAsset> assets();

    /** 获取并校验行情，保留来源的日期和可比值。 */
    Map<MarketAsset, Quote> fetch();

    /** 来源归一化后的价格与比较数据，FRED 仅提供交易日时 observedAt 为空。 */
    record Quote(BigDecimal price, BigDecimal previousPrice, BigDecimal changeAmount,
                 BigDecimal changePercent, LocalDate marketDate, Instant observedAt) {
    }
}
