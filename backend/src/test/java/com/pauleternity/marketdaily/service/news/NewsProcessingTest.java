package com.pauleternity.marketdaily.service.news;

import com.pauleternity.marketdaily.client.news.NewsFeed;
import com.pauleternity.marketdaily.model.news.NewsMarket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证新闻时间、来源链接、归类和事件去重。 */
class NewsProcessingTest {

    // 固定采集窗口及代表样本时间，测试不依赖本机日期。
    private static final Instant THROUGH = Instant.parse("2026-11-19T08:00:00Z");
    private static final Instant FROM = Instant.parse("2026-11-18T08:00:00Z");
    private static final Instant SEEN = Instant.parse("2026-11-19T00:00:00Z");

    private final NewsFilterService filter = new NewsFilterService();
    private final NewsDedupService dedup = new NewsDedupService();

    /** 区分收录时间与发布时间，跨市场分类并保留业务查询参数。 */
    @Test
    void keepsSeenTimeAndCanonicalizesArticleUrl() {
        var item = new NewsFeed.Item("Federal Reserve rate decision drives Nasdaq and gold",
                "https://WWW.example.org/news?id=12&utm_medium=email#page", "English", null, null, SEEN);
        var candidate = filter.filter(item, FROM, THROUGH, false);

        assertThat(candidate.url()).isEqualTo("https://www.example.org/news?id=12");
        assertThat(candidate.markets()).containsExactlyInAnyOrderElementsOf(Set.of(NewsMarket.GOLD, NewsMarket.NASDAQ));
        assertThat(candidate.newsAt()).isEqualTo(SEEN);
        assertThat(candidate.publishedAt()).isNull();
        assertThat(candidate.summary()).isNull();
        assertThat(dedup.urlHash(candidate.url())).isEqualTo(dedup.urlHash("http://www.example.org/news?id=12"));
    }

    /** 无可信时间、非 HTTP 链接和明确广告不会被写入。 */
    @Test
    void dropsMissingTimeUnsafeUrlAndPromotion() {
        assertThat(filter.filter(new NewsFeed.Item("Bitcoin regulation update", "https://example.org/news",
                "en", null, null, null), FROM, THROUGH, false)).isNull();
        assertThat(filter.filter(new NewsFeed.Item("Bitcoin regulation update", "javascript:alert(1)",
                "en", null, null, SEEN), FROM, THROUGH, false)).isNull();
        assertThat(filter.filter(new NewsFeed.Item("Gold advice paid promotion", "https://example.org/news",
                "en", null, null, SEEN), FROM, THROUGH, false)).isNull();
    }

    /** 同一事件的近似标题合并，不把两个 Fed 主题或不同数值合并。 */
    @Test
    void mergesOnlySimilarTitlesWithinEventWindow() {
        String rateCut = "federal reserve cuts interest rates amid slowing inflation";
        String coverage = "the federal reserve cuts interest rates amid slowing inflation today";
        assertThat(dedup.sameEvent(rateCut, SEEN, coverage, SEEN.plusSeconds(3600))).isTrue();
        assertThat(dedup.sameEvent(rateCut, SEEN,
                "federal reserve releases minutes discussing labor demand", SEEN)).isFalse();
        assertThat(dedup.sameEvent("gold climbs after 25 basis point rate cut", SEEN,
                "gold climbs after 50 basis point rate cut", SEEN)).isFalse();
        assertThat(dedup.sameEvent(rateCut, SEEN, rateCut, SEEN.plusSeconds(48 * 3600L))).isFalse();
    }

    /** 同样的重大新闻优先选择官方来源。 */
    @Test
    void ranksOfficialSourceAboveUnverifiedPublisher() {
        String title = "Federal Reserve issues FOMC rate decision";
        var official = filter.filter(new NewsFeed.Item(title, "https://www.federalreserve.gov/news.htm",
                "en", null, SEEN, null), FROM, THROUGH, true);
        var other = filter.filter(new NewsFeed.Item(title, "https://example.org/blog",
                "en", null, SEEN, null), FROM, THROUGH, false);
        var score = new NewsScoreService("40,30,20,10");
        assertThat(score.score(official, THROUGH, 24)).isGreaterThan(score.score(other, THROUGH, 24));
    }
}
