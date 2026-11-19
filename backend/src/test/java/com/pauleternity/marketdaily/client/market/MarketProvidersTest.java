package com.pauleternity.marketdaily.client.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import com.pauleternity.marketdaily.shared.SourceHttp;
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

/** 用本地响应校验贵金属、纳指和比特币的来源字段与日期口径。 */
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

    /** 黄金请求失败时仍保存白银，并验证白银使用独立的 XAG 请求。 */
    @Test
    void keepsSilverWhenGoldRequestFails() {
        var builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                request.url().getPath().endsWith("/XAG")
                        ? ClientResponse.create(HttpStatus.OK).body("""
                            {"symbol":"XAG","currency":"USD","price":45.43,"updatedAt":"2026-11-19T00:00:00Z"}
                            """).build()
                        : ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build()));
        var quotes = new GoldQuoteProvider(new SourceHttp(builder, new ObjectMapper())).fetch();
        assertThat(quotes).containsOnlyKeys(MarketAsset.XAGUSD);
        assertThat(quotes.get(MarketAsset.XAGUSD).price()).isEqualByComparingTo("45.43");
    }

    /** 上金所金银同日记录分别取收盘价，按各自的交易日期比较。 */
    @Test
    void parsesDomesticMetalClosesByContract() {
        var http = mockHttp("""
                <table class="daily_new_table">
                  <tr><th>日期</th><th>合约</th><th>开盘价</th><th>收盘价</th></tr>
                  <tr><td>2026-01-06</td><td>Au99.99</td><td>850.02</td><td>848.05</td></tr>
                  <tr><td>2026-01-06</td><td>Ag(T+D)</td><td>10,200</td><td>10,250</td></tr>
                  <tr><td>2026-01-08</td><td>Au99.95</td><td>855.00</td><td>856.00</td></tr>
                  <tr><td>2026-01-07</td><td>Au99.99</td><td>846.02</td><td>849.20</td></tr>
                  <tr><td>2026-01-07</td><td>Ag(T+D)</td><td>10,250</td><td>10,312</td></tr>
                </table>
                """);
        // 同一份上金所响应需要返回两种资产的独立报价。
        var quotes = new SgeQuoteProvider(http).fetch();
        assertThat(quotes).containsOnlyKeys(MarketAsset.AU9999, MarketAsset.AGTD);
        var quote = quotes.get(MarketAsset.AU9999);
        assertThat(quote.price()).isEqualByComparingTo("849.20");
        assertThat(quote.previousPrice()).isEqualByComparingTo("848.05");
        assertThat(quote.marketDate()).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(quote.observedAt()).isNull();
        var silver = quotes.get(MarketAsset.AGTD);
        assertThat(silver.price()).isEqualByComparingTo("10312");
        assertThat(silver.previousPrice()).isEqualByComparingTo("10250");
        assertThat(silver.marketDate()).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(silver.observedAt()).isNull();
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

    /** BTC 涨跌幅采用来源的 24 小时百分比。 */
    @Test
    void keepsBitcoinRollingChangeFromSource() {
        var provider = new CoinGeckoQuoteProvider(mockHttp("""
                [
                  {"id":"bitcoin","current_price":80000,"price_change_24h":-123.12,
                   "price_change_percentage_24h":-0.1537,"last_updated":"2026-11-19T00:00:00Z"}
                ]
                """), "test-only");

        var quotes = provider.fetch();
        assertThat(quotes).containsOnlyKeys(MarketAsset.BTCUSD);
        assertThat(quotes.get(MarketAsset.BTCUSD).changePercent()).isEqualByComparingTo("-0.1537");
        assertThat(quotes.get(MarketAsset.BTCUSD).previousPrice()).isEqualByComparingTo("80123.12");
    }

    /** 拦截 HTTP 交换，为 Provider 提供可重复的 JSON 响应。 */
    private SourceHttp mockHttp(String body) {
        var builder = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse
                .create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build()));
        return new SourceHttp(builder, new ObjectMapper());
    }
}
