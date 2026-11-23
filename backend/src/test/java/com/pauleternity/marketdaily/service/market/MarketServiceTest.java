package com.pauleternity.marketdaily.service.market;

import com.pauleternity.marketdaily.client.market.MarketQuoteProvider;
import com.pauleternity.marketdaily.client.market.MarketQuoteProvider.Quote;
import com.pauleternity.marketdaily.mapper.MarketPriceMapper;
import com.pauleternity.marketdaily.model.market.HourlyQuote;
import com.pauleternity.marketdaily.model.market.LatestQuote;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import com.pauleternity.marketdaily.model.market.MarketSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 验证采集隔离、日度比较和查询降级。 */
class MarketServiceTest {

    // 固定的北京时间业务日期，避免测试在午夜前后使用两套日期。
    private static final LocalDate DAY = LocalDate.of(2026, 11, 19);
    // 每小时第 5 分钟采样，和后端默认配置一致。
    private static final String DEFAULT_HOURLY_CRON = "0 5 * * * *";

    /** 黄金与上次采样比较，另一个来源失败仍记录状态并返回旧值。 */
    @Test
    void isolatesSourceFailureAndRetainsPreviousQuote() {
        var mapper = mock(MarketPriceMapper.class);
        var gold = mock(MarketQuoteProvider.class);
        var fred = mock(MarketQuoteProvider.class);
        when(gold.assets()).thenReturn(Set.of(MarketAsset.XAUUSD));
        when(fred.assets()).thenReturn(Set.of(MarketAsset.NASDAQCOM));
        when(fred.fetch()).thenThrow(new IllegalStateException());
        when(gold.fetch()).thenReturn(Map.of(MarketAsset.XAUUSD, new Quote(new BigDecimal("2100"),
                null, null, null, DAY, Instant.parse("2026-11-19T00:00:00Z"))));
        when(mapper.previousDailySample("XAUUSD", "Gold API", DAY)).thenReturn(new MarketSnapshot("XAUUSD", "黄金",
                new BigDecimal("2000"), null, null, null, "USD", "PREVIOUS_DAILY_SAMPLE", DAY.minusDays(1),
                Instant.parse("2026-11-18T00:00:00Z"), Instant.parse("2026-11-18T00:00:00Z"),
                DAY.minusDays(1), "Gold API", "AVAILABLE"));

        // 模拟写入后，纳指当天查询到失败标记，最新报价仍来自昨天的有效快照。
        var oldQuote = new MarketSnapshot("NASDAQCOM", "纳指", new BigDecimal("20000"), null,
                null, null, "POINTS", "PREVIOUS_TRADING_CLOSE", DAY.minusDays(2), null,
                Instant.parse("2026-11-18T00:00:00Z"), DAY.minusDays(1), "FRED", "AVAILABLE");
        when(mapper.latestBefore("NASDAQCOM", "FRED", DAY)).thenReturn(oldQuote);
        when(mapper.onDate("NASDAQCOM", "FRED", DAY)).thenReturn(new MarketSnapshot("NASDAQCOM", "纳指",
                null, null, null, null, "POINTS", "PREVIOUS_TRADING_CLOSE", null, null,
                Instant.parse("2026-11-19T00:00:00Z"), DAY, "FRED", "FAILED"));

        var latest = new MarketService(mapper, List.of(fred, gold), DEFAULT_HOURLY_CRON).collect(DAY);
        var writes = ArgumentCaptor.forClass(MarketSnapshot.class);
        verify(mapper, times(2)).save(writes.capture());
        assertThat(writes.getAllValues().getFirst().status()).isEqualTo("FAILED");
        assertThat(writes.getAllValues().getFirst().price()).isNull();
        assertThat(writes.getValue().changeAmount()).isEqualByComparingTo("100");
        assertThat(writes.getValue().changePercent()).isEqualByComparingTo("5");
        assertThat(latest).filteredOn(item -> item.symbol().equals("NASDAQCOM")).singleElement().satisfies(item -> {
            assertThat(item.collectionStatus()).isEqualTo("FAILED");
            assertThat(item.freshness()).isEqualTo("STALE");
            assertThat(item.quote()).isEqualTo(oldQuote);
        });
        assertThat(latest).extracting(LatestQuote::symbol)
                .containsExactly("XAUUSD", "AU9999", "XAGUSD", "AGTD", "NASDAQCOM", "BTCUSDT");
        assertThat(latest).filteredOn(item -> item.symbol().equals("XAGUSD")).singleElement()
                .satisfies(item -> assertThat(item.freshness()).isEqualTo("MISSING"));
    }

    /** 小时采集跳过日线来源，延迟到达的报价仍存到它实际发生的小时。 */
    @Test
    void samplesIntradayProvidersUsingObservedHour() {
        var mapper = mock(MarketPriceMapper.class);
        var silver = mock(MarketQuoteProvider.class);
        var fred = mock(MarketQuoteProvider.class);
        // 这条报价发生在当前测试运行前，归档时须使用来源小时。
        var observed = Instant.parse("2026-11-19T03:47:02Z");
        when(silver.assets()).thenReturn(Set.of(MarketAsset.XAGUSD));
        when(fred.assets()).thenReturn(Set.of(MarketAsset.NASDAQCOM));
        when(silver.fetch()).thenReturn(Map.of(MarketAsset.XAGUSD, new Quote(new BigDecimal("45.36"),
                null, null, null, DAY, observed)));

        new MarketService(mapper, List.of(fred, silver), DEFAULT_HOURLY_CRON).collectHourly();

        verify(fred, never()).fetch();
        verify(mapper).saveHourly("XAGUSD", "Gold API", Instant.parse("2026-11-19T03:00:00Z"),
                observed, new BigDecimal("45.36"));
    }

    /** 日线定时任务请求上金所和 FRED，国际盘中来源继续由小时任务负责。 */
    @Test
    void schedulesDailyProvidersSeparately() {
        var mapper = mock(MarketPriceMapper.class);
        var domestic = mock(MarketQuoteProvider.class);
        var fred = mock(MarketQuoteProvider.class);
        var btc = mock(MarketQuoteProvider.class);
        when(domestic.assets()).thenReturn(Set.of(MarketAsset.AU9999, MarketAsset.AGTD));
        when(fred.assets()).thenReturn(Set.of(MarketAsset.NASDAQCOM));
        when(btc.assets()).thenReturn(Set.of(MarketAsset.BTCUSDT));

        new MarketService(mapper, List.of(domestic, fred, btc), DEFAULT_HOURLY_CRON).collectDaily();

        verify(domestic).fetch();
        verify(fred).fetch();
        verify(btc, never()).fetch();
        verify(mapper, times(3)).save(any());
    }

    /** 币安小时报价和日度快照使用独立的 USDT 标的及来源键。 */
    @Test
    void storesBinanceQuoteInSeparateUsdtSeries() {
        var mapper = mock(MarketPriceMapper.class);
        var btc = mock(MarketQuoteProvider.class);
        // 23:58 北京时间的窗口结束时刻，入库到 15:00 UTC 小时桶。
        var observed = Instant.parse("2026-11-19T15:58:46Z");
        var price = new BigDecimal("61431.89");
        when(btc.assets()).thenReturn(Set.of(MarketAsset.BTCUSDT));
        when(btc.fetch()).thenReturn(Map.of(MarketAsset.BTCUSDT, new Quote(price,
                new BigDecimal("61278.32"), new BigDecimal("153.57"), new BigDecimal("0.251"), DAY, observed)));

        new MarketService(mapper, List.of(btc), DEFAULT_HOURLY_CRON).collectHourly();

        verify(mapper).saveHourly("BTCUSDT", "币安现货", Instant.parse("2026-11-19T15:00:00Z"), observed, price);
        var snapshot = ArgumentCaptor.forClass(MarketSnapshot.class);
        verify(mapper).save(snapshot.capture());
        assertThat(snapshot.getValue().symbol()).isEqualTo("BTCUSDT");
        assertThat(snapshot.getValue().currency()).isEqualTo("USDT");
        assertThat(snapshot.getValue().changePercent()).isEqualByComparingTo("0.251");
    }

    /** 跨北京时间午夜时，小时窗口仍保留包含当前小时的连续 24 格。 */
    @Test
    void queriesTwentyFourHourlyBuckets() {
        var mapper = mock(MarketPriceMapper.class);
        // 23:00 到次日 22:00（北京时间），两端均计入窗口。
        var from = Instant.parse("2026-11-18T15:00:00Z");
        var through = Instant.parse("2026-11-19T14:00:00Z");
        var point = new HourlyQuote(through, new BigDecimal("63300"));
        when(mapper.hourlyHistory("BTCUSD", "CoinGecko", from, through)).thenReturn(List.of(point));

        var history = new MarketService(mapper, List.of(), DEFAULT_HOURLY_CRON)
                .hourlyHistory(MarketAsset.BTCUSD, Instant.parse("2026-11-19T14:25:00Z"));

        assertThat(history.from()).isEqualTo(from);
        assertThat(history.through()).isEqualTo(through);
        assertThat(history.points()).containsExactly(point);
        assertThat(history.closedSamplingHours()).isEmpty();
    }

    /** 午休缺口跟随采样时刻，增加开盘后的采样时，缺报仍保留为断点。 */
    @Test
    void classifiesDomesticClosureFromScheduledSamples() {
        var mapper = mock(MarketPriceMapper.class);
        // 北京时间 12、13 时在默认的 :05 采样时均休市，14 时已有日盘。
        var noon = Instant.parse("2026-11-19T04:00:00Z");
        var one = Instant.parse("2026-11-19T05:00:00Z");
        var two = Instant.parse("2026-11-19T06:00:00Z");
        var standard = new MarketService(mapper, List.of(), DEFAULT_HOURLY_CRON);
        var twicePerHour = new MarketService(mapper, List.of(), "0 5,35 * * * *");
        var disabled = new MarketService(mapper, List.of(), "-");

        assertThat(standard.hourlyHistory(MarketAsset.AU9999, two).closedSamplingHours())
                .contains(noon, one).doesNotContain(two);
        assertThat(twicePerHour.hourlyHistory(MarketAsset.AGTD, two).closedSamplingHours())
                .contains(noon).doesNotContain(one, two);
        assertThat(disabled.hourlyHistory(MarketAsset.AU9999, two).closedSamplingHours()).isEmpty();
    }
}
