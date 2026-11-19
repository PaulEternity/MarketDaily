package com.pauleternity.marketdaily.client.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/** 从 FRED 获取纳斯达克综合指数的有效日收盘观测。 */
@Component
class FredQuoteProvider implements MarketQuoteProvider {

    private final SourceHttp http;
    private final String apiKey;

    /** FRED 密钥从运行环境注入。 */
    FredQuoteProvider(SourceHttp http, @Value("${FRED_API_KEY:}") String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    /** FRED 来源仅负责纳斯达克综合指数。 */
    @Override
    public Set<MarketAsset> assets() {
        return Set.of(MarketAsset.NASDAQCOM);
    }

    /** 按交易日倒序跳过休市空值，取最新和上一个有效收盘价。 */
    @Override
    public Map<MarketAsset, Quote> fetch() {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("FRED_API_KEY 未配置");
        }
        var uri = UriComponentsBuilder.fromUriString("https://api.stlouisfed.org/fred/series/observations")
                .queryParam("series_id", "NASDAQCOM")
                .queryParam("api_key", "{key}")
                .queryParam("file_type", "json")
                .queryParam("sort_order", "desc")
                .queryParam("limit", 30)
                .encode().buildAndExpand(apiKey).toUri();
        JsonNode observations = http.read(http.get().uri(uri)).path("observations");
        if (!observations.isArray()) {
            return Map.of();
        }

        // 保留最新有效交易日和点位，下一条更早的有效记录仅用于对比。
        LocalDate marketDate = null;
        BigDecimal latest = null;
        BigDecimal previous = null;
        for (JsonNode row : observations) {
            BigDecimal price = SourceHttp.decimal(row.path("value"));
            if (price == null || price.signum() <= 0) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(row.path("date").asText(""));
            } catch (DateTimeParseException ex) {
                continue;
            }
            if (latest == null) {
                latest = price;
                marketDate = date;
            } else if (date.isBefore(marketDate)) {
                previous = price;
                break;
            }
        }
        return latest == null ? Map.of() : Map.of(MarketAsset.NASDAQCOM,
                new Quote(latest, previous, null, null, marketDate, null));
    }
}
