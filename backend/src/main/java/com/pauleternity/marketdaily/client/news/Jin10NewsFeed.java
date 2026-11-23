package com.pauleternity.marketdaily.client.news;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从金十 MCP 拉取财经快讯，沿用 MarketDaily 的新闻清洗与入库流程。 */
@Component
@Conditional(Jin10NewsFeed.TokenConfigured.class)
class Jin10NewsFeed implements NewsFeed {

    private static final Logger log = LoggerFactory.getLogger(Jin10NewsFeed.class);
    private static final String ENDPOINT = "https://mcp.jin10.com/mcp";
    private static final String TOOL = "list_flash";
    private static final String SEARCH_TOOL = "search_flash";
    // 每项只读首屏，避免热点快讯把油价、央行决议挤出通用列表。
    private static final List<String> MACRO_TERMS = List.of("原油", "OPEC", "美联储", "利率决议", "日本央行", "欧洲央行");
    // shortcut: 每轮最多取 8 页，新闻密度高时优先覆盖近期；需要完整窗口时接入按主题检索。
    private static final int MAX_PAGES = 8;
    // 每个 MCP 请求在 5 秒内收取对应结果，避免长期占用采集线程。
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient client;
    private final ObjectMapper json;
    // 金十颁发的鉴权凭据，只在固定的 MCP 端点请求中使用。
    private final String token;

    /** 使用项目已有 WebClient 与 Jackson，单独处理 MCP 流式响应。 */
    Jin10NewsFeed(WebClient.Builder builder, ObjectMapper json, @Value("${JIN10_MCP_TOKEN:}") String token) {
        this.client = builder.clone().codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024 * 1024)).build();
        this.json = json;
        this.token = token.strip();
    }

    /** 返回快讯采集结果中的来源键。 */
    @Override
    public String source() {
        return "JIN10_FLASH";
    }

    /** 按金十从新到旧的快讯页采集，遇到窗口边界、末页或重复游标时停止。 */
    @Override
    public List<Item> fetch(int hours) {
        // 窗口起点用于提前停止翻页，精确的入库窗口由新闻服务统一校验。
        Instant cutoff = Instant.now().minus(Duration.ofHours(hours));
        List<Item> items = new ArrayList<>();
        // 保存已用游标，防止上游重复返回同一页；首屏请求不传参数。
        Set<String> usedCursors = new HashSet<>();
        Map<String, String> arguments = Map.of();
        String cursorParameter = null;
        for (int pageIndex = 0; pageIndex < MAX_PAGES; pageIndex++) {
            JsonNode page = flashPage(arguments);
            JsonNode rows = page.path("items");
            if (!rows.isArray()) {
                throw new IllegalStateException("金十快讯缺少列表");
            }
            boolean reachedWindowStart = false;
            for (JsonNode row : rows) {
                Instant publishedAt = SourceHttp.instant(row.path("time"));
                if (publishedAt == null) {
                    continue;
                }
                if (publishedAt.isBefore(cutoff)) {
                    reachedWindowStart = true;
                    continue;
                }
                Item item = flashItem(row, publishedAt);
                if (item != null) items.add(item);
            }
            if (reachedWindowStart || rows.isEmpty() || !page.path("has_more").asBoolean()
                    || pageIndex + 1 == MAX_PAGES) {
                break;
            }
            String cursor = page.path("next_cursor").asText(page.path("next_offset").asText(""));
            if (cursor.isBlank() || !usedCursors.add(cursor)) {
                break;
            }
            // 首屏实测无需参数，翻页时再从工具定义读取该账号支持的参数名。
            if (cursorParameter == null) {
                cursorParameter = cursorParameter();
                if (cursorParameter == null) {
                    break;
                }
            }
            arguments = Map.of(cursorParameter, cursor);
        }
        return items;
    }

    /** 读取快讯工具结果，优先取结构化 JSON，并兼容文本封装的 JSON。 */
    private JsonNode flashPage(Map<String, String> arguments) {
        return flashPage(TOOL, arguments);
    }

    /** 主题检索走独立来源，搜索失败时普通快讯仍可入库。 */
    @Bean
    NewsFeed jin10MacroFeed() {
        return new NewsFeed() {
            /** 与普通快讯分开记录来源状态。 */
            @Override public String source() { return "JIN10_MACRO"; }
            /** 采集主题快讯，时间窗口由新闻清洗统一校验。 */
            @Override public List<Item> fetch(int hours) { return searchMacroFlashes(); }
        };
    }

    /** 从运行时工具定义识别搜索词字段，逐个主题检索快讯。 */
    private List<Item> searchMacroFlashes() {
        String argument = null;
        for (JsonNode tool : request("tools/list", Map.of()).path("tools")) {
            if (!SEARCH_TOOL.equals(tool.path("name").asText())) continue;
            var schema = tool.path("inputSchema");
            for (String key : List.of("keyword", "query", "keywords", "q")) {
                if ("string".equals(schema.path("properties").path(key).path("type").asText())) {
                    argument = key;
                    break;
                }
            }
            break;
        }
        if (argument == null) throw new IllegalStateException("金十宏观搜索缺少搜索词参数");
        List<Item> items = new ArrayList<>();
        // 单个关键词失败时保留其他主题的结果，全组失败才标记来源不可用。
        int successful = 0;
        for (String keyword : MACRO_TERMS) {
            JsonNode rows;
            try {
                rows = flashPage(SEARCH_TOOL, Map.of(argument, keyword)).path("items");
                if (!rows.isArray()) throw new IllegalStateException("金十搜索缺少快讯列表");
                successful++;
            } catch (RuntimeException ex) {
                log.warn("金十宏观快讯检索失败，关键词={}，异常={}", keyword, ex.getClass().getSimpleName());
                continue;
            }
            for (JsonNode row : rows) {
                var publishedAt = SourceHttp.instant(row.path("time"));
                Item item = publishedAt == null ? null : flashItem(row, publishedAt);
                if (item != null) items.add(item);
            }
        }
        if (successful == 0) throw new IllegalStateException("金十宏观快讯全部检索失败");
        return items;
    }

    /** 普通快讯和搜索结果使用同一响应格式。 */
    private JsonNode flashPage(String tool, Map<String, String> arguments) {
        JsonNode result = request("tools/call", Map.of("name", tool, "arguments", arguments));
        JsonNode payload = result.path("structuredContent");
        if (!payload.isObject()) {
            payload = parseJson(result.path("content").path(0).path("text").asText(""));
        }
        if (result.path("isError").asBoolean() || payload.path("status").asInt() != 200) {
            throw new IllegalStateException("金十快讯业务调用失败");
        }
        return payload.path("data");
    }

    /** 实测快讯可省略标题，优先从正文的【标题】段提取。 */
    private static Item flashItem(JsonNode row, Instant publishedAt) {
        String content = row.path("content").asText("").strip();
        String title = row.path("title").asText("").strip();
        String url = row.path("url").asText("");
        return url.isBlank() || (title.isBlank() && content.isBlank()) ? null
                : new Item(title.isBlank() ? flashTitle(content) : title, url, "zh", content, publishedAt, null);
    }

    /** 按工具输入定义选择实测游标或旧文档中的偏移参数。 */
    private String cursorParameter() {
        for (JsonNode tool : request("tools/list", Map.of()).path("tools")) {
            if (TOOL.equals(tool.path("name").asText())) {
                JsonNode properties = tool.path("inputSchema").path("properties");
                for (String parameter : List.of("cursor", "offset")) {
                    if (properties.has(parameter)) {
                        return parameter;
                    }
                }
            }
        }
        return null;
    }

    /** 调用已通过独立请求验证的 MCP 端点，兼容 SSE 与普通 JSON。 */
    private JsonNode request(String method, Map<String, ?> params) {
        // 同一时间只等待一个请求，使用固定 JSON-RPC ID 过滤无关 SSE 通知。
        int requestId = 1;
        Map<String, ?> rpc = Map.of("jsonrpc", "2.0", "id", requestId, "method", method, "params", params);
        JsonNode reply = client.post().uri(ENDPOINT).headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .bodyValue(rpc).exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.createError();
                    }
                    if (response.headers().contentType().filter(MediaType.TEXT_EVENT_STREAM::isCompatibleWith).isPresent()) {
                        return response.bodyToFlux(String.class).filter(event -> !event.isBlank()).map(this::parseJson)
                                .filter(event -> event.path("id").asInt(-1) == requestId).next();
                    }
                    return response.bodyToMono(JsonNode.class);
                }).timeout(REQUEST_TIMEOUT).block();
        if (reply == null || reply.path("id").asInt(-1) != requestId || !reply.path("result").isObject()) {
            throw new IllegalStateException("金十 MCP 请求未返回结果");
        }
        return reply.path("result");
    }

    /** 解析事件或工具正文，异常信息不携带请求凭据和响应正文。 */
    private JsonNode parseJson(String value) {
        try {
            JsonNode document = json.readTree(value);
            if (document != null && document.isObject()) {
                return document;
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("金十 MCP 响应无法解析");
        }
        throw new IllegalStateException("金十 MCP 响应缺少 JSON 对象");
    }

    /** 正文没有【标题】时取首句，原文链接仍指向对应快讯。 */
    private static String flashTitle(String content) {
        int bracket = content.indexOf('】');
        if (content.startsWith("【") && bracket > 1) {
            return content.substring(1, bracket).strip();
        }
        int sentence = content.indexOf('。');
        return sentence > 0 ? content.substring(0, sentence).strip() : content;
    }

    /** 仅在部署配置有凭据时注册该来源。 */
    static final class TokenConfigured implements Condition {
        /** 空 Token 对应未启用，不进入新闻来源列表。 */
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !context.getEnvironment().getProperty("JIN10_MCP_TOKEN", "").isBlank();
        }
    }
}
