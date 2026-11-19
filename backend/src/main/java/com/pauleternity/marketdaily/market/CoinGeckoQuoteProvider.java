package com.pauleternity.marketdaily.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** 批量获取 CoinGecko 的 BTC、ETH 美元行情。 */
@Component
class CoinGeckoQuoteProvider implements MarketQuoteProvider {

    private final MarketHttp http;
    private final String apiKey;

    /** CoinGecko Demo 密钥从运行环境注入。 */
    CoinGeckoQuoteProvider(MarketHttp http, @Value("${COINGECKO_API_KEY:}") String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    /** 一个批量请求覆盖两类资产。 */
    @Override
    public Set<MarketAsset> assets() {
        return Set.of(MarketAsset.BTCUSD, MarketAsset.ETHUSD);
    }

    /** 分别校验两种资产，保留来源提供的滚动 24 小时变化值与百分比。 */
    @Override
    public Map<MarketAsset, Quote> fetch() {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("COINGECKO_API_KEY 未配置");
        }
        JsonNode response = http.read(http.get()
                .uri("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=bitcoin,ethereum")
                .header("x-cg-demo-api-key", apiKey));
        // 单项行情异常只会让该资产缺失，不丢弃另一项。
        Map<MarketAsset, Quote> quotes = new EnumMap<>(MarketAsset.class);
        if (!response.isArray()) {
            return quotes;
        }
        for (JsonNode row : response) {
            MarketAsset asset = switch (row.path("id").asText()) {
                case "bitcoin" -> MarketAsset.BTCUSD;
                case "ethereum" -> MarketAsset.ETHUSD;
                default -> null;
            };
            BigDecimal price = MarketHttp.decimal(row.path("current_price"));
            Instant observedAt = MarketHttp.instant(row.path("last_updated"));
            if (asset == null || price == null || price.signum() <= 0 || observedAt == null) {
                continue;
            }

            // 24 小时前价格从来源的变化金额倒推，百分比直接使用来源给出的口径。
            BigDecimal change = MarketHttp.decimal(row.path("price_change_24h"));
            BigDecimal previous = change == null ? null : price.subtract(change);
            quotes.put(asset, new Quote(price, previous != null && previous.signum() > 0 ? previous : null,
                    change, MarketHttp.decimal(row.path("price_change_percentage_24h")),
                    observedAt.atZone(MarketAsset.REPORT_ZONE).toLocalDate(), observedAt));
        }
        return quotes;
    }
}
