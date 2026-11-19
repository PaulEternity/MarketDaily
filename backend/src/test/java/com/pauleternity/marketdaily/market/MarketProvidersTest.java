package com.pauleternity.marketdaily.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 用本地响应校验三个来源的字段与日期口径。 */
class MarketProvidersTest {

    /** 黄金保留上游时间和原始小数精度，即便北京时间已跨日。 */
    @Test
    void parsesGoldPriceAndTimestamp() {
        var provider = new GoldQuoteProvider(mockHttp("""
                {"symbol":"XAU","price":2345.12345678,"updatedAt":"2026-11-18T16:01:00Z"}
                """));

        var quote = provider.fetch().get(MarketAsset.XAUUSD);
        assertThat(quote.price()).isEqualByComparingTo("2345.12345678");
        assertThat(quote.observedAt()).isEqualTo(Instant.parse("2026-11-18T16:01:00Z"));
        assertThat(quote.marketDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(quote.previousPrice()).isNull();
    }

    /** FRED 休市缺值不会变成 0，也不会把交易日补成猜测的收盘时刻。 */
    @Test
    void skipsMissingFredObservations() {
        var provider = new FredQuoteProvider(mockHttp("""
                {"observations":[
                    {"date":"2026-11-27","value":"20010.15"},
                    {"date":"2026-11-26","value":"."},
                    {"date":"2026-11-25","value":"19900.10"}
                ]}
                """), "test-only");

        var quote = provider.fetch().get(MarketAsset.NASDAQCOM);
        assertThat(quote.marketDate()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(quote.price()).isEqualByComparingTo("20010.15");
        assertThat(quote.previousPrice()).isEqualByComparingTo("19900.10");
        assertThat(quote.observedAt()).isNull();
    }

    /** ETH 缺失时 BTC 仍然可用，涨跌幅采用来源的 24 小时百分比。 */
    @Test
    void keepsValidCoinWhenOtherCoinIsMissing() {
        var provider = new CoinGeckoQuoteProvider(mockHttp("""
                [
                  {"id":"bitcoin","current_price":80000,"price_change_24h":-123.12,
                   "price_change_percentage_24h":-0.1537,"last_updated":"2026-11-19T00:00:00Z"},
                  {"id":"ethereum","current_price":null}
                ]
                """), "test-only");

        var quotes = provider.fetch();
        assertThat(quotes).containsOnlyKeys(MarketAsset.BTCUSD);
        assertThat(quotes.get(MarketAsset.BTCUSD).changePercent()).isEqualByComparingTo("-0.1537");
        assertThat(quotes.get(MarketAsset.BTCUSD).previousPrice()).isEqualByComparingTo("80123.12");
    }

    /** 拦截 HTTP 交换，为 Provider 提供可重复的 JSON 响应。 */
    private MarketHttp mockHttp(String body) {
        var builder = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse
                .create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build()));
        return new MarketHttp(builder, new ObjectMapper());
    }
}
