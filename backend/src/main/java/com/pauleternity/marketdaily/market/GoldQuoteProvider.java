package com.pauleternity.marketdaily.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** 获取 Gold API 的美元每盎司国际现货金银报价。 */
@Component
class GoldQuoteProvider implements MarketQuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(GoldQuoteProvider.class);

    // Gold API 使用金属代码，入库使用含计价币种的资产代码。
    private static final Map<MarketAsset, String> SOURCE_SYMBOLS = Map.of(
            MarketAsset.XAUUSD, "XAU", MarketAsset.XAGUSD, "XAG");

    private final SourceHttp http;

    /** 注入统一的行情客户端。 */
    GoldQuoteProvider(SourceHttp http) {
        this.http = http;
    }

    /** 同一行情源负责两种国际贵金属。 */
    @Override
    public Set<MarketAsset> assets() {
        return SOURCE_SYMBOLS.keySet();
    }

    /** 分别采集金银报价，单一品种失败时保留另一份有效结果。 */
    @Override
    public Map<MarketAsset, Quote> fetch() {
        Map<MarketAsset, Quote> quotes = new EnumMap<>(MarketAsset.class);
        SOURCE_SYMBOLS.forEach((asset, symbol) -> {
            try {
                Quote quote = readQuote(symbol);
                if (quote != null) {
                    quotes.put(asset, quote);
                }
            } catch (RuntimeException failure) {
                log.warn("国际贵金属采集失败，资产={}，异常类型={}", asset, failure.getClass().getSimpleName());
            }
        });
        return quotes;
    }

    /** 校验来源标识、计价币种和更新时间。 */
    private Quote readQuote(String symbol) {
        JsonNode response = http.read(http.get().uri("https://api.gold-api.com/price/{symbol}", symbol));
        BigDecimal price = SourceHttp.decimal(response.path("price"));
        Instant observedAt = SourceHttp.instant(response.path("updatedAt"));
        if (!symbol.equals(response.path("symbol").asText()) || !"USD".equals(response.path("currency").asText("USD"))
                || price == null || price.signum() <= 0 || observedAt == null) {
            return null;
        }
        return new Quote(price, null, null, null, observedAt.atZone(MarketAsset.REPORT_ZONE).toLocalDate(), observedAt);
    }
}
