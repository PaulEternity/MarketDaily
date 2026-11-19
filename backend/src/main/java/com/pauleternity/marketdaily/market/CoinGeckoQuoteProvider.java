package com.pauleternity.marketdaily.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** 获取 CoinGecko 的 BTC 美元行情。 */
@Component
class CoinGeckoQuoteProvider implements MarketQuoteProvider {

    private final SourceHttp http;
    private final String apiKey;

    /** CoinGecko Demo 密钥从运行环境注入。 */
    CoinGeckoQuoteProvider(SourceHttp http, @Value("${COINGECKO_API_KEY:}") String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    /** 继续采集 BTC，已移出看板的 ETH 只保留历史数据。 */
    @Override
    public Set<MarketAsset> assets() {
        return Set.of(MarketAsset.BTCUSD);
    }

    /** 读取 BTC 价格，保留来源提供的滚动 24 小时变化值与百分比。 */
    @Override
    public Map<MarketAsset, Quote> fetch() {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("COINGECKO_API_KEY 未配置");
        }
        JsonNode response = http.read(http.get()
                .uri("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=bitcoin")
                .header("x-cg-demo-api-key", apiKey));
        JsonNode row = response.path(0);
        BigDecimal price = SourceHttp.decimal(row.path("current_price"));
        Instant observedAt = SourceHttp.instant(row.path("last_updated"));
        if (!"bitcoin".equals(row.path("id").asText()) || price == null || price.signum() <= 0 || observedAt == null) {
            return Map.of();
        }

        // 24 小时前价格从来源的变化金额倒推，百分比沿用来源口径。
        BigDecimal change = SourceHttp.decimal(row.path("price_change_24h"));
        BigDecimal previous = change == null ? null : price.subtract(change);
        return Map.of(MarketAsset.BTCUSD, new Quote(price,
                previous != null && previous.signum() > 0 ? previous : null,
                change, SourceHttp.decimal(row.path("price_change_percentage_24h")),
                observedAt.atZone(MarketAsset.REPORT_ZONE).toLocalDate(), observedAt));
    }
}
