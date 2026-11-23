package com.pauleternity.marketdaily.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.pauleternity.marketdaily.model.report.ReportContent.MacroObservation;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 读取可独立缺失的宏观观测，保留报价日和原始来源用于日报。 */
@Component
class MacroMarketData {
    private static final Logger log = LoggerFactory.getLogger(MacroMarketData.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final String BIS_URL = "https://stats.bis.org/api/v1/data/BIS,WS_CBPOL,1.0/D.US+XM+JP+GB+CN/all";
    private static final String BIS_PAGE = "https://data.bis.org/topics/CBPOL";
    private static final Map<String, String> POLICY_AREAS = Map.of(
            "US", "美国", "XM", "欧元区", "JP", "日本", "GB", "英国", "CN", "中国");

    private final WebClient client;
    private final String fredApiKey;

    /** FRED 复用现有密钥，BIS 公开数据无需密钥。 */
    MacroMarketData(WebClient.Builder builder, @Value("${FRED_API_KEY:}") String fredApiKey) {
        this.client = builder.build();
        this.fredApiKey = fredApiKey;
    }

    /** 分开读取实际利率和央行政策利率，上游故障只省略对应观测。 */
    List<MacroObservation> latest(LocalDate through) {
        List<MacroObservation> observations = new ArrayList<>();
        if (!fredApiKey.isBlank()) {
            try {
                observations.addAll(realYield(through));
            } catch (RuntimeException ex) {
                log.warn("宏观数据读取失败，来源=FRED DFII10，异常={}", ex.getClass().getSimpleName());
            }
        }
        try {
            var uri = UriComponentsBuilder.fromUriString(BIS_URL).queryParam("endPeriod", through)
                    .queryParam("lastNObservations", 1).build().toUri();
            String csv = client.get().uri(uri).header("Accept", "application/vnd.sdmx.data+csv;version=1.0.0")
                    .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
            if (csv != null) observations.addAll(policyRates(csv, through));
        } catch (RuntimeException ex) {
            log.warn("宏观数据读取失败，来源=BIS，异常={}", ex.getClass().getSimpleName());
        }
        return List.copyOf(observations);
    }

    /** 美国实际利率用 10 年期 TIPS 收益率，保留最近两条用于比较。 */
    private List<MacroObservation> realYield(LocalDate through) {
        var uri = UriComponentsBuilder.fromUriString("https://api.stlouisfed.org/fred/series/observations")
                .queryParam("series_id", "DFII10").queryParam("api_key", "{key}").queryParam("file_type", "json")
                .queryParam("sort_order", "desc").queryParam("limit", 14).queryParam("observation_end", through)
                .encode().buildAndExpand(fredApiKey).toUri();
        JsonNode response = client.get().uri(uri).retrieve().bodyToMono(JsonNode.class).timeout(TIMEOUT).block();
        List<MacroObservation> values = new ArrayList<>();
        if (response == null) return values;
        for (JsonNode row : response.path("observations")) {
            BigDecimal value = SourceHttp.decimal(row.path("value"));
            if (value == null) continue;
            LocalDate date = LocalDate.parse(row.path("date").asText());
            if (!date.isAfter(through)) values.add(new MacroObservation("美国10年期实际收益率", date, value,
                    "FRED DFII10", "https://fred.stlouisfed.org/series/DFII10"));
            if (values.size() == 2) break;
        }
        return values;
    }

    /** BIS CSV 前五列是未加引号的固定字段，后面的说明可能含逗号。 */
    static List<MacroObservation> policyRates(String csv, LocalDate through) {
        var rows = csv.lines().iterator();
        if (!rows.hasNext() || !rows.next().replace("\uFEFF", "").startsWith("DATAFLOW,FREQ,REF_AREA,TIME_PERIOD,OBS_VALUE,")) {
            throw new IllegalStateException("BIS 利率 CSV 缺少预期列");
        }
        // 每个经济体保留截止日日前最新的一条观测，利率数值单位为百分数。
        Map<String, MacroObservation> rates = new LinkedHashMap<>();
        while (rows.hasNext()) {
            String[] fields = rows.next().split(",", 6);
            if (fields.length < 5 || !POLICY_AREAS.containsKey(fields[2]) || fields[4].isBlank()) continue;
            try {
                LocalDate date = LocalDate.parse(fields[3]);
                if (date.isAfter(through)) continue;
                var rate = new MacroObservation(POLICY_AREAS.get(fields[2]) + "政策利率", date,
                        new BigDecimal(fields[4]), "BIS CBPOL", BIS_PAGE);
                rates.merge(fields[2], rate, (old, fresh) -> old.date().isAfter(fresh.date()) ? old : fresh);
            } catch (NumberFormatException | DateTimeParseException ignored) {
                // 跳过缺值或坏行，其余经济体仍可使用。
            }
        }
        return List.copyOf(rates.values());
    }
}
