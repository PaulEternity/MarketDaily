package com.pauleternity.marketdaily.market;

import com.pauleternity.marketdaily.shared.SourceHttp;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** 读取上金所国内黄金和白银的收盘价。 */
@Component
class SgeQuoteProvider implements MarketQuoteProvider {

    // 上游合约对应本地资产代码，金价为元/克，白银为元/千克。
    private static final Map<String, MarketAsset> CONTRACTS = Map.of(
            "Au99.99", MarketAsset.AU9999, "Ag(T+D)", MarketAsset.AGTD);

    private final SourceHttp http;

    /** 注入与市场其他来源共用的 HTTP 客户端。 */
    SgeQuoteProvider(SourceHttp http) {
        this.http = http;
    }

    /** 声明上金所提供的两个国内贵金属资产。 */
    @Override
    public Set<MarketAsset> assets() {
        return Set.of(MarketAsset.AU9999, MarketAsset.AGTD);
    }

    /** 一次查询两种合约，分别取最近两个有效交易日。 */
    @Override
    public Map<MarketAsset, Quote> fetch() {
        // 拉取近两周以跨过周末和节假日，上金所日行情不提供收盘时间戳。
        LocalDate today = LocalDate.now(MarketAsset.REPORT_ZONE);
        var uri = UriComponentsBuilder.fromUriString("https://www.sge.com.cn/sjzx/quotation_daily_new")
                .queryParam("start_date", today.minusDays(14)).queryParam("end_date", today)
                // 使用 URI 变量编码合约里的 +，避免被服务端当成空格。
                .queryParam("inst_ids", "{contracts}").encode()
                .buildAndExpand(String.join(",", CONTRACTS.keySet())).toUri();
        // 每个合约的收盘记录按交易日倒序排列，缺报价的合约不会出现在结果中。
        var byContract = SgeDailyTable.recentCloses(http.readBody(http.get().uri(uri)), today, CONTRACTS.keySet());
        var quotes = new EnumMap<MarketAsset, Quote>(MarketAsset.class);
        byContract.forEach((contract, closes) -> {
            SgeDailyTable.Close latest = closes.getFirst();
            BigDecimal previous = closes.size() > 1 ? closes.get(1).price() : null;
            quotes.put(CONTRACTS.get(contract), new Quote(latest.price(), previous, null, null, latest.date(), null));
        });
        return quotes;
    }
}
