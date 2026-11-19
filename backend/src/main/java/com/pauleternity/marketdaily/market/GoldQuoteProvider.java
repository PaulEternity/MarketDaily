package com.pauleternity.marketdaily.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** 获取 Gold API 的美元每盎司现货金价。 */
@Component
class GoldQuoteProvider implements MarketQuoteProvider {

    private final MarketHttp http;

    /** 注入统一的行情客户端。 */
    GoldQuoteProvider(MarketHttp http) {
        this.http = http;
    }

    /** 黄金来源仅负责 XAUUSD。 */
    @Override
    public Set<MarketAsset> assets() {
        return Set.of(MarketAsset.XAUUSD);
    }

    /** 读取实时价格和来源更新时间，可比价格在入库前从历史快照取。 */
    @Override
    public Map<MarketAsset, Quote> fetch() {
        JsonNode response = http.read(http.get().uri("https://api.gold-api.com/price/XAU"));
        BigDecimal price = MarketHttp.decimal(response.path("price"));
        Instant observedAt = MarketHttp.instant(response.path("updatedAt"));
        if (!"XAU".equals(response.path("symbol").asText()) || price == null || price.signum() <= 0
                || observedAt == null) {
            return Map.of();
        }
        return Map.of(MarketAsset.XAUUSD, new Quote(price, null, null, null,
                observedAt.atZone(MarketAsset.REPORT_ZONE).toLocalDate(), observedAt));
    }
}
