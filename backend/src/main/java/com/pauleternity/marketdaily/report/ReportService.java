package com.pauleternity.marketdaily.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 管理日报生成、证据校验以及只读历史。 */
@Service
class ReportService {

    // 日报日期以北京时间为准，模型与数据库时间戳使用 UTC。
    static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    private final ReportMapper reports;
    private final ReportInput inputs;
    private final ReportModelClient model;
    private final ObjectMapper json;

    /** 复用数据库、输入整理和模型客户端。 */
    ReportService(ReportMapper reports, ReportInput inputs, ReportModelClient model, ObjectMapper json) {
        this.reports = reports;
        this.inputs = inputs;
        this.model = model;
        this.json = json;
    }

    /** 同一实例并发触发时串行检查，同一天已有结果直接返回。 */
    synchronized Generation generateToday() {
        Instant now = Instant.now();
        LocalDate date = now.atZone(REPORT_ZONE).toLocalDate();
        Detail previous = onDate(date);
        if (previous != null) {
            return new Generation(true, previous);
        }
        // 固定本轮输入；模型返回后只允许引用这个快照里的新闻。
        var input = inputs.snapshot(date, now);
        if (input.news().isEmpty() && input.prices().stream().noneMatch(price -> price.price() != null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "暂无可用行情或新闻，请先采集数据");
        }
        var result = model.generate(input);
        var analysis = validate(result.content(), input);
        var content = new ReportContent(analysis, input);
        var stored = new ReportMapper.Stored(date, analysis.summary(), encode(content), hash(encode(input)),
                result.model(), ReportModelClient.PROMPT_VERSION, result.inputTokens(), result.outputTokens(),
                Instant.now());
        reports.insert(stored);
        return new Generation(false, detail(stored));
    }

    /** 查询指定日期，缺少记录返回空。 */
    Detail onDate(LocalDate date) {
        var stored = reports.onDate(date);
        return stored == null ? null : detail(stored);
    }

    /** 历史结果按业务日期倒序，不触发生成。 */
    Page history(int page, int size) {
        long offset = (page - 1L) * size;
        return new Page(page, size, reports.count(), reports.page(size, offset));
    }

    /** 校验固定板块、文本长度、新闻来源及板块归属。 */
    static ReportContent.Analysis validate(JsonNode body, ReportContent.Input input) {
        if (body == null || !body.isObject()) {
            throw ReportModelClient.invalid("日报根节点必须是对象");
        }
        String summary = requiredText(body.path("summary"), "summary", 300);
        Map<Long, ReportContent.Article> articles = input.news().stream()
                .collect(Collectors.toMap(ReportContent.Article::id, item -> item));
        // 模型按固定键输出，转换成已约定的有序 sections，前端和历史格式保持一致。
        var sections = new ArrayList<ReportContent.Section>();
        boolean hasCitation = false;
        for (String market : ReportModelClient.MARKETS) {
            JsonNode item = body.path(market);
            if (!item.isObject()) {
                throw ReportModelClient.invalid("日报缺少 " + market + " 板块对象");
            }
            if (item.size() != 3) {
                throw ReportModelClient.invalid(market + " 板块必须仅包含 analysis、watch 和 newsIds");
            }
            JsonNode references = item.path("newsIds");
            if (!references.isArray() || references.size() > 3) {
                throw ReportModelClient.invalid(market + ".newsIds 必须是最多三项的数组");
            }
            var ids = new ArrayList<Long>();
            for (JsonNode ref : references) {
                if (!ref.isIntegralNumber() || !ref.canConvertToLong()) {
                    throw ReportModelClient.invalid(market + ".newsIds 只能包含整数 ID");
                }
                long id = ref.longValue();
                var article = articles.get(id);
                if (article == null || !article.markets().contains(market) || ids.contains(id)) {
                    throw ReportModelClient.invalid(market + " 的新闻引用无效：ID 不在该板块快照内或重复");
                }
                ids.add(id);
                hasCitation = true;
            }
            sections.add(new ReportContent.Section(market, requiredText(item.path("analysis"), market + ".analysis", 400),
                    requiredText(item.path("watch"), market + ".watch", 400), List.copyOf(ids)));
        }
        if (body.size() != 1 + ReportModelClient.MARKETS.size()) {
            throw ReportModelClient.invalid("日报含有未约定的顶层字段");
        }
        if (!articles.isEmpty() && !hasCitation) {
            throw ReportModelClient.invalid("输入含有新闻，但日报没有引用任何新闻");
        }
        return new ReportContent.Analysis(summary, List.copyOf(sections));
    }

    /** 要求纯文本字段存在，前端按文本显示。 */
    private static String requiredText(JsonNode value, String field, int max) {
        String text = value.isTextual() ? value.textValue().strip() : "";
        if (text.isEmpty()) {
            throw ReportModelClient.invalid(field + " 必须是非空文本");
        }
        if (text.codePointCount(0, text.length()) > max) {
            throw ReportModelClient.invalid(field + " 超出长度限制");
        }
        return text;
    }

    /** 从同一个已保存快照恢复内容和生成信息。 */
    private Detail detail(ReportMapper.Stored stored) {
        try {
            return new Detail(stored.date(), stored.model(), stored.promptVersion(),
                    stored.inputTokens(), stored.outputTokens(), stored.generatedAt(),
                    json.readValue(stored.content(), ReportContent.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("数据库中的日报内容无法读取");
        }
    }

    /** 将输入或完整报告编码为 JSON，不输出原文到错误日志。 */
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("日报内容无法序列化");
        }
    }

    /** 记录稳定的输入指纹，便于追溯同一批证据。 */
    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法计算日报输入指纹");
        }
    }

    /** 详情含输入快照，历史记录不会随行情或新闻变化。 */
    record Detail(LocalDate date, String model, String promptVersion, Integer inputTokens,
                  Integer outputTokens, Instant generatedAt, ReportContent content) {
    }

    /** reused 表示直接复用了同一业务日的已保存结果。 */
    record Generation(boolean reused, Detail report) {
    }

    /** 分页查询结果。 */
    record Page(int page, int size, long total, List<ReportMapper.Summary> items) {
    }
}
