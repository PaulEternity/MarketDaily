package com.pauleternity.marketdaily.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/** 通过一次 GDELT 请求检索三个市场的新闻元数据。 */
@Component
class GdeltNewsFeed implements NewsFeed {

    // 上游 seendate 使用 UTC 紧凑格式，表示收录而非发布时间。
    private static final DateTimeFormatter SEEN_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withResolverStyle(ResolverStyle.STRICT);

    // 查询词覆盖黄金、纳斯达克、加密货币及共同宏观事件。
    private static final String DEFAULT_QUERY = "(\"gold price\" OR Nasdaq OR Bitcoin OR Ethereum OR "
            + "\"Federal Reserve\" OR inflation OR \"interest rates\" OR Nvidia OR \"crypto regulation\")";

    private final SourceHttp http;
    private final String query;

    /** 查询表达式可由部署环境调整，默认覆盖三个市场。 */
    GdeltNewsFeed(SourceHttp http, @Value("${NEWS_GDELT_QUERY:}") String query) {
        this.http = http;
        this.query = query.isBlank() ? DEFAULT_QUERY : query;
    }

    /** GDELT 新闻数据源名称。 */
    @Override
    public String source() {
        return "GDELT";
    }

    /** 仅读取标题和链接等元数据，不访问文章原文。 */
    @Override
    public List<Item> fetch(int hours) {
        var uri = UriComponentsBuilder.fromUriString("https://api.gdeltproject.org/api/v2/doc/doc")
                .queryParam("query", "{query}").queryParam("mode", "artlist").queryParam("format", "json")
                .queryParam("timespan", hours + "h").queryParam("maxrecords", 150)
                .queryParam("sort", "HybridRel").encode().buildAndExpand(query).toUri();
        JsonNode response = http.read(http.get().uri(uri));
        JsonNode articles = response.path("articles");
        // GDELT 以空对象或空数组表示没有命中，其他响应结构视作来源异常。
        if (response.isObject() && response.isEmpty()) {
            return List.of();
        }
        if (!articles.isArray()) {
            throw new IllegalStateException("GDELT 响应缺少新闻列表");
        }
        List<Item> items = new ArrayList<>();
        for (JsonNode row : articles) {
            items.add(new Item(row.path("title").asText(""), row.path("url").asText(""),
                    row.path("language").asText(null), null, null, seenAt(row.path("seendate").asText(""))));
        }
        return items;
    }

    /** 无效收录时间交给清洗层过滤，不使用采集时间补齐。 */
    private Instant seenAt(String value) {
        try {
            return LocalDateTime.parse(value, SEEN_TIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
