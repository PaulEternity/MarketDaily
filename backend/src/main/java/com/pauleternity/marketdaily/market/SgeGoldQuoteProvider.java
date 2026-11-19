package com.pauleternity.marketdaily.market;

import com.pauleternity.marketdaily.shared.SourceHttp;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** 读取上金所 Au99.99 最近交易日收盘价，单位为人民币每克。 */
@Component
class SgeGoldQuoteProvider implements MarketQuoteProvider {

    private final SourceHttp http;

    /** 注入与市场其他来源共用的 HTTP 客户端。 */
    SgeGoldQuoteProvider(SourceHttp http) {
        this.http = http;
    }

    /** 国内黄金独立采集，失败时不影响国际金银报价。 */
    @Override
    public Set<MarketAsset> assets() {
        return Set.of(MarketAsset.AU9999);
    }

    /** 取最近两个有效交易日，保留交易日期和上一收盘价。 */
    @Override
    public Map<MarketAsset, Quote> fetch() {
        // 拉取近两周以跨过周末和节假日，上金所日行情不提供收盘时间戳。
        LocalDate today = LocalDate.now(MarketAsset.REPORT_ZONE);
        var uri = UriComponentsBuilder.fromUriString("https://www.sge.com.cn/sjzx/quotation_daily_new")
                .queryParam("start_date", today.minusDays(14)).queryParam("end_date", today)
                .queryParam("inst_ids", "Au99.99").build().toUri();
        var closes = SgeDailyTable.recentCloses(http.readBody(http.get().uri(uri)), today);
        if (closes.isEmpty()) {
            return Map.of();
        }
        SgeDailyTable.Close latest = closes.getFirst();
        BigDecimal previous = closes.size() > 1 ? closes.get(1).price() : null;
        return Map.of(MarketAsset.AU9999, new Quote(latest.price(), previous, null, null, latest.date(), null));
    }
}
