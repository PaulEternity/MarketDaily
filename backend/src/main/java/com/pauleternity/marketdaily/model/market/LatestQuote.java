package com.pauleternity.marketdaily.model.market;

/** 行情为空时仍暴露资产信息和当天采集状态，区分缺失和旧值。 */
public record LatestQuote(String symbol, String name, String unit, String source, String comparisonType,
              String freshness, String collectionStatus, MarketSnapshot quote) {
}
