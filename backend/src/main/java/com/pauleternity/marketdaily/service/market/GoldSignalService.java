package com.pauleternity.marketdaily.service.market;

import com.pauleternity.marketdaily.mapper.GoldSignalMapper;
import com.pauleternity.marketdaily.model.market.GoldSignals;
import com.pauleternity.marketdaily.model.market.GoldSignals.Bar;
import com.pauleternity.marketdaily.model.market.GoldSignals.Breakouts;
import com.pauleternity.marketdaily.model.market.GoldSignals.Period;
import com.pauleternity.marketdaily.model.market.GoldSignals.Symbol;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeMap;

/** 对已完成的黄金 K 线计算突破统计，并统一校验采集和导入数据。 */
@Service
public class GoldSignalService {

    // 国内日线由公开日行情提供，分时可使用同一市场的授权来源。
    public static final String SGE_DAILY_SOURCE = "上海黄金交易所每日行情";
    private final GoldSignalMapper bars;

    /** 注入独立于现有报价快照的 K 线存储。 */
    public GoldSignalService(GoldSignalMapper bars) {
        this.bars = bars;
    }

    /** 列出三个黄金品种的最近 72 个已完成小时。 */
    public List<GoldSignals> overview(Instant now) {
        return List.of(read(Symbol.AU9999, now), read(Symbol.XAUUSD, now), read(Symbol.GC, now));
    }

    /** 选择最新 K 线所属的单一来源，禁止把不同供应商的小时线拼成一条。 */
    private GoldSignals read(Symbol symbol, Instant now) {
        // 72 小时是自然时间窗口；休市或数据缺失的小时不会被补造。
        Instant to = now.truncatedTo(ChronoUnit.HOURS);
        Instant from = to.minus(72, ChronoUnit.HOURS);
        var hourly = latestSource(bars.range(symbol, Period.HOUR, from, to));
        var allDaily = bars.range(symbol, Period.DAY, from.minus(30, ChronoUnit.DAYS), now).stream()
                .filter(bar -> !endsAt(bar).isAfter(now)).toList();
        // 国内使用同一交易所的官方日线；其他品种仅用同来源的日线做比较。
        var official = allDaily.stream().filter(bar -> SGE_DAILY_SOURCE.equals(bar.source())).toList();
        var daily = symbol == Symbol.AU9999 && !official.isEmpty() ? official
                : hourly.isEmpty() ? latestSource(allDaily)
                : allDaily.stream().filter(bar -> bar.source().equals(hourly.getLast().source())).toList();
        // 仅在图表返回最近七个有数据的交易日；比较允许跨周末和长假找参照。
        return new GoldSignals(symbol, symbol.unit, symbol.volumeUnit, from, to, hourly,
                daily.subList(Math.max(0, daily.size() - 7), daily.size()), summarize(hourly, daily));
    }

    /** 让最新一条有效记录决定来源，来源切换造成的数据缺口留空。 */
    private static List<Bar> latestSource(List<Bar> rows) {
        if (rows.isEmpty()) return rows;
        String source = rows.getLast().source();
        return rows.stream().filter(bar -> bar.source().equals(source)).toList();
    }

    /** 每根小时线只计一次；严格越过高低点才算突破，收盘也需严格越过。 */
    static Breakouts summarize(List<Bar> hourly, List<Bar> daily) {
        // 按交易日选严格早于当前 K 线交易日的最近一根日线。
        var references = new TreeMap<LocalDate, Bar>();
        daily.forEach(bar -> references.put(bar.tradingDate(), bar));
        // 分别累计有参照、盘中突破及收盘确认的小时数。
        int compared = 0, high = 0, highClose = 0, low = 0, lowClose = 0;
        for (Bar bar : hourly) {
            var reference = references.lowerEntry(bar.tradingDate());
            if (reference == null || endsAt(reference.getValue()).isAfter(bar.startTime())) continue;
            Bar previous = reference.getValue();
            compared++;
            if (bar.highPrice().compareTo(previous.highPrice()) > 0) high++;
            if (bar.closePrice().compareTo(previous.highPrice()) > 0) highClose++;
            if (bar.lowPrice().compareTo(previous.lowPrice()) < 0) low++;
            if (bar.closePrice().compareTo(previous.lowPrice()) < 0) lowClose++;
        }
        return new Breakouts(compared, high, highClose, low, lowClose);
    }

    /** 全批校验后入库，重复导入覆盖相同数据键。 */
    @Transactional
    public int save(List<Bar> batch, Instant now) {
        if (batch == null || batch.isEmpty() || batch.size() > 1000) throw invalid("每批须包含 1 至 1000 根 K 线");
        for (Bar bar : batch) validate(bar, now);
        batch.forEach(bars::save);
        return batch.size();
    }

    /** 确保导入的时间、价格与方向成交量满足对应的数据口径。 */
    private static void validate(Bar bar, Instant now) {
        if (bar == null || bar.symbol() == null || bar.period() == null || bar.startTime() == null
                || bar.tradingDate() == null || bar.source() == null || bar.source().isBlank()
                || bar.source().length() > 100 || !bar.source().equals(bar.source().strip())
                || bar.source().chars().anyMatch(Character::isISOControl)) throw invalid("K 线基础字段不合法");
        // 拒绝无法存入 MySQL 的日期；日线用 UTC 午夜保存交易日，非实际开盘时间。
        if (bar.tradingDate().getYear() < 1970 || bar.tradingDate().getYear() > 9998
                || bar.startTime().isBefore(Instant.EPOCH) || bar.startTime().isAfter(now)
                || !bar.startTime().equals(bar.startTime().truncatedTo(ChronoUnit.HOURS))
                || (bar.period() == Period.DAY && !bar.startTime().equals(
                        bar.tradingDate().atStartOfDay().toInstant(ZoneOffset.UTC)))
                || endsAt(bar).isAfter(now)) throw invalid("仅接受时间对齐的已完成 K 线");
        // Gold API 的交易日按 UTC 自然日划分；国内与期货由上游提供交易归属。
        if (bar.period() == Period.HOUR && bar.symbol() == Symbol.XAUUSD
                && !bar.startTime().atOffset(ZoneOffset.UTC).toLocalDate().equals(bar.tradingDate())) {
            throw invalid("XAUUSD 小时 K 线的交易日须使用 UTC 日期");
        }
        if (!price(bar.openPrice()) || !price(bar.highPrice()) || !price(bar.lowPrice()) || !price(bar.closePrice())
                || bar.highPrice().compareTo(bar.openPrice().max(bar.closePrice())) < 0
                || bar.lowPrice().compareTo(bar.openPrice().min(bar.closePrice())) > 0) throw invalid("OHLC 价格或精度不合法");
        if (!volume(bar.volume()) || !volume(bar.buyVolume()) || !volume(bar.sellVolume())
                || (bar.symbol() == Symbol.XAUUSD && bar.volume() != null)
                || (bar.symbol() == Symbol.AU9999 && bar.period() == Period.DAY && bar.buyVolume() != null)
                || ((bar.buyVolume() == null) != (bar.sellVolume() == null))
                || (bar.buyVolume() != null && (bar.volume() == null
                    || bar.buyVolume().add(bar.sellVolume()).compareTo(bar.volume()) > 0))) throw invalid("成交量或买卖方向口径不合法");
    }

    /** 对日度接口保留实际收盘门槛，防止跨日统计使用未完成的日线。 */
    private static Instant endsAt(Bar bar) {
        if (bar.period() == Period.HOUR) return bar.startTime().plus(1, ChronoUnit.HOURS);
        if (bar.symbol() == Symbol.AU9999) {
            return bar.tradingDate().atTime(LocalTime.of(15, 30)).atZone(MarketAsset.REPORT_ZONE).toInstant();
        }
        return bar.tradingDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** 正价格须落在 MySQL 十进制列的有效位数内。 */
    private static boolean price(BigDecimal value) {
        return value != null && value.signum() > 0 && fits(value, 16);
    }

    /** 成交量允许为空，零表示来源确认没有相应成交。 */
    private static boolean volume(BigDecimal value) {
        return value == null || (value.signum() >= 0 && fits(value, 20));
    }

    /** 校验整数和小数位数，不让数据库悄悄截断。 */
    private static boolean fits(BigDecimal value, int wholeDigits) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() <= 8 && normalized.precision() - (long) normalized.scale() <= wholeDigits;
    }

    /** 为管理界面返回可修正的数据校验错误。 */
    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
