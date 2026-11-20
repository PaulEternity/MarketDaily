package com.pauleternity.marketdaily.model.market;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 黄金走势窗口。GC 是期货参考品种，不与国际现货合并。 */
public record GoldSignals(Symbol symbol, String unit, String volumeUnit, Instant from, Instant to,
                          List<Bar> hourly, List<Bar> daily, Breakouts breakouts) {

    /** 品种对应的价格及成交量口径。 */
    public enum Symbol {
        AU9999("CNY/g", "千克"),
        XAUUSD("USD/oz", null),
        GC("USD/oz", "手（单向）");

        public final String unit;
        public final String volumeUnit;

        /** 固定每个市场的单位，避免导入数据改变图表口径。 */
        Symbol(String unit, String volumeUnit) {
            this.unit = unit;
            this.volumeUnit = volumeUnit;
        }
    }

    /** HOUR 用 UTC 小时起点，DAY 用交易日作为日期键。 */
    public enum Period { HOUR, DAY }

    /** 已完成的 K 线。主动成交量与总量须使用同一口径；未知字段保持为空。 */
    public record Bar(Symbol symbol, Period period, String source, Instant startTime, LocalDate tradingDate,
                      BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice, BigDecimal closePrice,
                      BigDecimal volume, BigDecimal buyVolume, BigDecimal sellVolume) {

        /** 仅在主动买入与卖出都可用时计算主动成交量差。 */
        @JsonProperty("netVolume")
        public BigDecimal netVolume() {
            return buyVolume == null || sellVolume == null ? null : buyVolume.subtract(sellVolume);
        }

        /** 仅在总量和两个方向齐全时计算剩余的方向未知量。 */
        @JsonProperty("unknownVolume")
        public BigDecimal unknownVolume() {
            return volume == null || netVolume() == null ? null : volume.subtract(buyVolume).subtract(sellVolume);
        }
    }

    /** 统计小时 K 线数量。缺参照的小时不计入比较。 */
    public record Breakouts(int comparedHours, int highBreaks, int highCloses, int lowBreaks, int lowCloses) {
    }
}
