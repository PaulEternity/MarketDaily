package com.pauleternity.marketdaily.client.news;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomesticNewsFeedsTest {
    private final DomesticNewsFeeds feeds = new DomesticNewsFeeds();

    @Test
    void readsSgeNoticeDateAndTitleWithoutDecorativeIcons() {
        var feed = (DomesticNewsFeeds.OfficialList) feeds.sgeNewsFeed(null);
        var items = feed.parse("""
                <ul><li><a href="/jjsnotice/10007666">
                  <i>★</i><span class="txt fl">关于做好市场风险控制工作的通知</span><span class="fr">26-11-13</span>
                </a></li><li><a href="/jjsnotice/7"><span class="txt">日期错误</span><span class="fr">26-02-30</span>
                </a></li></ul>
                """);
        assertThat(items).containsExactly(new NewsFeed.Item("关于做好市场风险控制工作的通知",
                "https://www.sge.com.cn/jjsnotice/10007666", "zh", null,
                Instant.parse("2026-11-12T16:00:00Z"), null));
    }

    @Test
    void readsEachNbsReleaseOnceAndIgnoresOffSiteLinks() {
        var feed = (DomesticNewsFeeds.OfficialList) feeds.nbsNewsFeed(null);
        var items = feed.parse("""
                <div class="list-content"><ul>
                  <li><a class="fl pc_1600" href="./202611/t20261116_1965844.html">国民经济运行总体平稳</a>
                    <a class="fl pchide" href="./202611/t20261116_1965844.html">国民经济运行总体平稳</a>
                    <span>2026-11-16</span></li>
                  <li><a class="fl pc_1600" href="https://example.org/sj/zxfb/202611/t20261116_1965844.html">外站</a>
                    <span>2026-11-16</span></li>
                </ul></div>
                """);
        assertThat(items).containsExactly(new NewsFeed.Item("国民经济运行总体平稳",
                "https://www.stats.gov.cn/sj/zxfb/202611/t20261116_1965844.html", "zh", null,
                Instant.parse("2026-11-15T16:00:00Z"), null));
        assertThatThrownBy(() -> feed.parse("<html><title>服务暂不可用</title></html>"))
                .isInstanceOf(IllegalStateException.class);
    }
}
