package com.pauleternity.marketdaily.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pauleternity.marketdaily.shared.SourceHttp;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用本地响应验证两种新闻来源的原始时间和 XML 绑定。 */
class NewsFeedsTest {

    /** GDELT 时间是 UTC 收录时间，不能填入发布时间。 */
    @Test
    void readsGdeltSeenDateFromJson() {
        var feed = new GdeltNewsFeed(mockHttp("""
                {"articles":[{"title":"Gold price climbs","url":"https://example.org/gold",
                "seendate":"20261119T001530Z","language":"English"}]}
                """), "");

        assertThat(feed.fetch(24)).singleElement().satisfies(item -> {
            assertThat(item.seenAt()).isEqualTo(Instant.parse("2026-11-19T00:15:30Z"));
            assertThat(item.publishedAt()).isNull();
        });
    }

    /** 官方 RSS 包含 BOM，发布时间按原时区转换，遇到 DTD 直接拒绝。 */
    @Test
    void readsFedPublicationDateAndRejectsDtd() {
        var feed = new FedNewsFeed(mockHttp("\uFEFF" + """
                <rss version="2.0"><channel><item>
                <title>Federal Reserve issues FOMC statement</title>
                <link>https://www.federalreserve.gov/example.htm</link>
                <pubDate>Wed, 18 Nov 2026 14:00:00 -0500</pubDate>
                <description>Official summary.</description>
                </item></channel></rss>
                """));

        assertThat(feed.fetch(24)).singleElement().satisfies(item -> {
            assertThat(item.publishedAt()).isEqualTo(Instant.parse("2026-11-18T19:00:00Z"));
            assertThat(item.seenAt()).isNull();
        });
        assertThatThrownBy(() -> feed.parse("<!DOCTYPE rss><rss><channel/></rss>"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 拦截请求以复现固定供应商响应，不需要外部网络。 */
    private SourceHttp mockHttp(String body) {
        var builder = WebClient.builder().exchangeFunction(request ->
                Mono.just(ClientResponse.create(HttpStatus.OK).body(body).build()));
        return new SourceHttp(builder, new ObjectMapper());
    }
}
