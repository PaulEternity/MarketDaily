package com.pauleternity.marketdaily.client.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** 从币安公开行情获取 BTC/USDT 现货报价和滚动 24 小时涨跌。 */
@Component
class BinanceQuoteProvider implements MarketQuoteProvider {

    // 币安专用公开行情域名，北京服务器已实测此接口可返回现货报价。
    private static final String ENDPOINT = "https://data-api.binance.vision/api/v3/ticker/24hr?symbol=BTCUSDT";
    private final SourceHttp http;

    /** 复用现有行情请求的超时、重试和数字解析。 */
    BinanceQuoteProvider(SourceHttp http) {
        this.http = http;
    }

    /** 只采集币安 USDT 现货标的，历史美元标的使用原资产编号。 */
    @Override
    public Set<MarketAsset> assets() {
        return Set.of(MarketAsset.BTCUSDT);
    }

    /** 按币安统计窗口保存最新价格、比较价格和涨跌幅。 */
    @Override
    public Map<MarketAsset, Quote> fetch() {
        JsonNode row = http.read(http.get().uri(ENDPOINT));
        BigDecimal price = SourceHttp.decimal(row.path("lastPrice"));
        // closeTime 是滚动统计窗口的结束时间，单位毫秒，用于小时归档。
        long windowEndMillis = row.path("closeTime").asLong(-1);
        if (!MarketAsset.BTCUSDT.name().equals(row.path("symbol").asText())
                || price == null || price.signum() <= 0 || windowEndMillis <= 0) {
            return Map.of();
        }

        Instant observedAt = Instant.ofEpochMilli(windowEndMillis);
        // openPrice 对应同一滚动窗口的起点，币安已返回以百分数表示的涨跌幅。
        BigDecimal previous = SourceHttp.decimal(row.path("openPrice"));
        return Map.of(MarketAsset.BTCUSDT, new Quote(price,
                previous != null && previous.signum() > 0 ? previous : null,
                SourceHttp.decimal(row.path("priceChange")), SourceHttp.decimal(row.path("priceChangePercent")),
                observedAt.atZone(MarketAsset.REPORT_ZONE).toLocalDate(), observedAt));
    }
}
