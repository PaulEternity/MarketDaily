package com.pauleternity.marketdaily.client.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pauleternity.marketdaily.mapper.MarketPriceMapper;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 验证国内金银延时快照的日期、交易时段和价格过滤。 */
class SgeDelayedSamplerTest {

    // 以延时页的合约和最新价列模拟同一份金银报价，黄金价格带嵌套标签。
    private static final String PAGE = """
            <h1>上海黄金交易所%s延时行情</h1>
            <table>
                <tr><th>合约</th><th>最新价</th><th>最高价</th></tr>
                <tr><td>Au99.99</td><td><span>801.93</span></td><td>816.00</td></tr>
                <tr><td>Ag(T+D)</td><td>10,895.00</td><td>10,980.00</td></tr>
            </table>
            """;

    /** 两种合约同时存入采集小时，收盘快照不受影响。 */
    @Test
    void storesBothContractsUnderSamplingHour() {
        var mapper = mock(MarketPriceMapper.class);
        // 北京时间 10:05 是日盘时段。
        var sampledAt = Instant.parse("2026-11-19T02:05:00Z");
        var hour = Instant.parse("2026-11-19T02:00:00Z");
        var sampler = new SgeDelayedSampler(http(PAGE.formatted("2026年11月19日"), new AtomicInteger()), mapper);

        sampler.sample(sampledAt);

        verify(mapper).saveHourly("AU9999", "上海黄金交易所", hour, sampledAt, new BigDecimal("801.93"));
        verify(mapper).saveHourly("AGTD", "上海黄金交易所", hour, sampledAt, new BigDecimal("10895.00"));
        verifyNoMoreInteractions(mapper);
    }

    /** 页面日期过期或午休时不写入快照，午休也不发请求。 */
    @Test
    void ignoresStalePagesAndSkipsLunchBreak() {
        var mapper = mock(MarketPriceMapper.class);
        var requests = new AtomicInteger();
        var sampler = new SgeDelayedSampler(http(PAGE.formatted("2026年11月18日"), requests), mapper);

        sampler.sample(Instant.parse("2026-11-19T02:05:00Z"));
        sampler.sample(Instant.parse("2026-11-19T04:05:00Z"));

        assertThat(requests.get()).isEqualTo(1);
        verifyNoInteractions(mapper);
    }

    /** 周六凌晨仍可能属于周五夜盘，只保存有效报价。 */
    @Test
    void acceptsFridayNightIntoSaturdayAndSkipsZeroPrices() {
        var mapper = mock(MarketPriceMapper.class);
        var sampledAt = Instant.parse("2026-11-20T17:05:00Z");
        var mondayPage = PAGE.formatted("2026年11月23日").replace("801.93", "0.0");
        var sampler = new SgeDelayedSampler(http(mondayPage, new AtomicInteger()), mapper);

        sampler.sample(sampledAt);

        verify(mapper).saveHourly("AGTD", "上海黄金交易所", Instant.parse("2026-11-20T17:00:00Z"),
                sampledAt, new BigDecimal("10895.00"));
        verifyNoMoreInteractions(mapper);
    }

    /** 用受控 HTML 响应复查行情客户端的读取链路。 */
    private SourceHttp http(String html, AtomicInteger requests) {
        var builder = WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).body(html).build());
        });
        return new SourceHttp(builder, new ObjectMapper());
    }
}
