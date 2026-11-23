package com.pauleternity.marketdaily.service.market;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证人民币折算使用正确的单位，并在主备源不可用时保留有效缓冲。 */
class CnyReferenceRateServiceTest {

    // 固定北京时间 2026-11-23 的查询时点，避免测试依赖机器日期。
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    /** 中国银行以 100 美元报价，表头定位确保采用折算价而非买入价。 */
    @Test
    void readsBocUsdRateAndCachesIt() {
        // 用含时分秒的发布时间复现实测表格的日期格式。
        String html = """
                <table><tr><th>货币名称</th><th>现汇买入价</th><th>中行折算价</th><th>发布日期</th></tr>
                <tr><td>欧元</td><td>765.5</td><td>767.4</td><td>2026/11/23</td></tr>
                <tr><td>美元</td><td>665.35</td><td>671.20</td><td>2026/11/23 18:16:12</td></tr></table>
                """;
        var calls = new AtomicInteger();
        var builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            assertThat(request.url().getHost()).isEqualTo("www.boc.cn");
            return Mono.just(reply(MediaType.TEXT_HTML, html));
        });
        var service = new CnyReferenceRateService(builder, "");

        var rate = service.latest(NOW);

        assertThat(rate.cnyPerUsd()).isEqualByComparingTo("6.7120");
        assertThat(rate.date()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(rate.source()).isEqualTo("中国银行折算价");
        assertThat(service.latest(NOW.plus(Duration.ofMinutes(29)))).isEqualTo(rate);
        assertThat(calls.get()).isEqualTo(1);
    }

    /** 中行失败时选取 FRED 的有效观测；两家都失败时按报价日判断缓存有效期。 */
    @Test
    void fallsBackToFredAndExpiresOldRateAfterBothSourcesFail() {
        // 置为 false 后模拟两条出站连接均不可用。
        var backupAvailable = new AtomicBoolean(true);
        var builder = WebClient.builder().exchangeFunction(request -> {
            if ("api.stlouisfed.org".equals(request.url().getHost()) && backupAvailable.get()) {
                assertThat(request.url().getQuery()).contains("series_id=DEXCHUS");
                return Mono.just(reply(MediaType.APPLICATION_JSON, """
                        {"observations":[{"date":"2026-11-20","value":"."},
                          {"date":"2026-11-19","value":"6.6710"}]}
                        """));
            }
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
        });
        var service = new CnyReferenceRateService(builder, "test-only");

        var rate = service.latest(NOW);
        assertThat(rate.source()).isEqualTo("FRED DEXCHUS");
        assertThat(rate.cnyPerUsd()).isEqualByComparingTo("6.6710");
        assertThat(rate.date()).isEqualTo(LocalDate.of(2026, 11, 19));

        backupAvailable.set(false);
        assertThat(service.latest(NOW.plus(Duration.ofHours(1)))).isEqualTo(rate);
        assertThat(service.latest(NOW.plus(Duration.ofDays(15)))).isNull();
    }

    /** 按来源使用的 MIME 类型模拟响应，测试不访问线上接口。 */
    private static ClientResponse reply(MediaType type, String body) {
        return ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, type.toString()).body(body).build();
    }
}
