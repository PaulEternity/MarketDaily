package com.pauleternity.marketdaily.client.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证金十实测的 SSE、无独立标题快讯与分页格式。 */
class Jin10NewsFeedTest {

    /** 首屏按正文提取标题，后续根据工具定义以实际游标翻页，并跳过窗口外旧闻。 */
    @Test
    void readsFlashAndFollowsDiscoveredCursor() throws Exception {
        var json = new ObjectMapper();
        // 使用相对时间覆盖 24 小时窗口内外，同时复现实测中的北京时间偏移。
        var recent = Instant.now().minus(1, ChronoUnit.HOURS);
        var olderThanWindow = recent.minus(24, ChronoUnit.HOURS);
        var first = """
                {"id":1,"result":{"structuredContent":{"status":200,"data":{"items":[
                  {"content":"【黄金走势关注美联储决议】现货黄金价格波动。","time":"%s","url":"https://flash.jin10.com/detail/1"}
                ],"has_more":true,"next_cursor":"1795425416923"}}}}
                """.formatted(recent.atOffset(ZoneOffset.ofHours(8)));
        var definitions = """
                {"id":1,"result":{"tools":[{"name":"list_flash","inputSchema":{"properties":{"cursor":{"type":"string"}}}}]}}
                """;
        var lastPayload = """
                {"status":200,"data":{"items":[
                  {"content":"窗口外的黄金快讯。","time":"%s","url":"https://flash.jin10.com/detail/2"}
                ],"has_more":true,"next_cursor":"older"}}
                """.formatted(olderThanWindow);
        var last = """
                {"id":1,"result":{"content":[{"type":"text","text":%s}]}}
                """.formatted(json.writeValueAsString(lastPayload));

        // 保存逐次 RPC 请求正文，以确认第一屏和末页之间只查一次工具定义。
        List<String> requests = new ArrayList<>();
        var replies = List.of(first, definitions, last);
        var builder = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer fixture-token");
            var mockRequest = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(mockRequest, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(mockRequest::getBodyAsString))
                    .map(body -> {
                        var response = replies.get(requests.size());
                        requests.add(body);
                        return ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE,
                                        MediaType.TEXT_EVENT_STREAM_VALUE)
                                .body("event: message\ndata: " + response.replace("\n", "") + "\n\n").build();
                    });
        });

        var items = new Jin10NewsFeed(builder, json, "fixture-token").fetch(24);

        assertThat(items).containsExactly(new NewsFeed.Item("黄金走势关注美联储决议",
                "https://flash.jin10.com/detail/1", "zh", "【黄金走势关注美联储决议】现货黄金价格波动。",
                recent, null));
        assertThat(requests).hasSize(3);
        assertThat(json.readTree(requests.get(0)).at("/params/arguments").isEmpty()).isTrue();
        assertThat(json.readTree(requests.get(1)).path("method").asText()).isEqualTo("tools/list");
        assertThat(json.readTree(requests.get(2)).at("/params/arguments/cursor").asText())
                .isEqualTo("1795425416923");
    }
}
