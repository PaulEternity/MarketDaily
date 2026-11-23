package com.pauleternity.marketdaily.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

/** 独立读取并缓存 USD/CNY 展示汇率，供看板估算人民币参考价。 */
@Service
public class CnyReferenceRateService {

    private static final Logger log = LoggerFactory.getLogger(CnyReferenceRateService.class);
    private static final String BOC = "中国银行折算价";
    private static final String FRED = "FRED DEXCHUS";
    // 主备各请求一次，每次最多 4 秒，保持在前端请求超时以内。
    private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(5);
    // 日期按北京时间；最多沿用 14 个自然日前的参考汇率，覆盖节假日或 FRED 发布延迟。
    private static final int MAX_AGE_DAYS = 14;

    private final WebClient client;
    private final String fredApiKey;
    // 进程内缓存最近的有效汇率和允许再次请求来源的时刻，统一在同一把锁下读写。
    private ReferenceRate cached;
    private Instant refreshAfter = Instant.MIN;

    /** 中行无需密钥，FRED 备用源复用部署环境的 FRED_API_KEY。 */
    CnyReferenceRateService(WebClient.Builder builder, @Value("${FRED_API_KEY:}") String fredApiKey) {
        this.client = builder.clone().build();
        this.fredApiKey = fredApiKey;
    }

    /** 优先中行，失败时查询 FRED 或沿用有效缓存；串行刷新以合并并发的首次请求。 */
    public synchronized ReferenceRate latest(Instant now) {
        LocalDate today = now.atZone(MarketAsset.REPORT_ZONE).toLocalDate();
        if (!now.isBefore(refreshAfter)) {
            ReferenceRate fresh = attempt(BOC, this::readBoc);
            if (!usable(fresh, today) && !fredApiKey.isBlank()) {
                fresh = attempt(FRED, this::readFred);
            }
            boolean refreshed = usable(fresh, today);
            // 备用源可能比已经缓存的中行数据更早，保留报价日期更新的一条。
            if (refreshed && (cached == null || !fresh.date().isBefore(cached.date()))) {
                cached = fresh;
            }
            refreshAfter = now.plus(refreshed ? CACHE_TTL : RETRY_AFTER_FAILURE);
        }
        return usable(cached, today) ? cached : null;
    }

    /** 从中行表头定位美元折算价，按每 100 美元的报价换算成 1 美元。 */
    private ReferenceRate readBoc() {
        String html = client.get().uri("https://www.boc.cn/sourcedb/whpj/index.html")
                .retrieve().bodyToMono(String.class).timeout(SOURCE_TIMEOUT).block();
        if (html == null) {
            return null;
        }
        for (var table : Jsoup.parse(html).select("table")) {
            var headers = table.select("th").eachText();
            int currencyColumn = headers.indexOf("货币名称");
            int rateColumn = headers.indexOf("中行折算价");
            int dateColumn = headers.indexOf("发布日期");
            if (currencyColumn < 0 || rateColumn < 0 || dateColumn < 0) {
                continue;
            }
            int lastColumn = Math.max(currencyColumn, Math.max(rateColumn, dateColumn));
            for (var row : table.select("tr:has(td)")) {
                var cells = row.children();
                if (cells.size() > lastColumn && "美元".equals(cells.get(currencyColumn).text())) {
                    BigDecimal cnyPerHundredDollars = new BigDecimal(cells.get(rateColumn).text());
                    // 日期列有时附带时分秒，报价日统一使用 ISO 日期。
                    String date = cells.get(dateColumn).text().split("\\s+", 2)[0].replace('/', '-');
                    return new ReferenceRate(cnyPerHundredDollars.movePointLeft(2), LocalDate.parse(date), BOC);
                }
            }
        }
        return null;
    }

    /** FRED DEXCHUS 已按每 1 美元计价，跳过无数值的休市日。 */
    private ReferenceRate readFred() {
        var uri = UriComponentsBuilder.fromUriString("https://api.stlouisfed.org/fred/series/observations")
                .queryParam("series_id", "DEXCHUS").queryParam("api_key", "{key}")
                .queryParam("file_type", "json").queryParam("sort_order", "desc").queryParam("limit", 20)
                .encode().buildAndExpand(fredApiKey).toUri();
        JsonNode response = client.get().uri(uri).retrieve().bodyToMono(JsonNode.class).timeout(SOURCE_TIMEOUT).block();
        if (response == null) {
            return null;
        }
        for (JsonNode row : response.path("observations")) {
            BigDecimal rate = SourceHttp.decimal(row.path("value"));
            if (rate != null && rate.signum() > 0) {
                try {
                    return new ReferenceRate(rate, LocalDate.parse(row.path("date").asText()), FRED);
                } catch (DateTimeParseException ignored) {
                    // 日期异常时继续检查同一响应中的下一个观测值。
                }
            }
        }
        return null;
    }

    /** 单个来源异常时走回退链路，日志只记录来源名称和异常类型。 */
    private ReferenceRate attempt(String source, Supplier<ReferenceRate> read) {
        try {
            return read.get();
        } catch (RuntimeException ex) {
            log.warn("人民币参考汇率读取失败，来源={}，异常类型={}", source, ex.getClass().getSimpleName());
            return null;
        }
    }

    /** 拒绝非正汇率、未来报价日和超过有效期的历史数据。 */
    private static boolean usable(ReferenceRate rate, LocalDate today) {
        return rate != null && rate.cnyPerUsd().signum() > 0
                && !rate.date().isBefore(today.minusDays(MAX_AGE_DAYS)) && !rate.date().isAfter(today);
    }

    /** 一美元折合的人民币金额及来源公布的报价日。 */
    public record ReferenceRate(BigDecimal cnyPerUsd, LocalDate date, String source) {
    }
}
