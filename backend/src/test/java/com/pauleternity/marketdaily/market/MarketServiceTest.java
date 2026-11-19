package com.pauleternity.marketdaily.market;

import com.pauleternity.marketdaily.market.MarketQuoteProvider.Quote;
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
        when(mapper.previousGoldSample(DAY)).thenReturn(new MarketSnapshot("XAUUSD", "黄金",
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

        var latest = new MarketService(mapper, List.of(fred, gold)).collect(DAY);
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
        assertThat(latest).filteredOn(item -> item.symbol().equals("ETHUSD")).singleElement()
                .satisfies(item -> assertThat(item.freshness()).isEqualTo("MISSING"));
    }
}
