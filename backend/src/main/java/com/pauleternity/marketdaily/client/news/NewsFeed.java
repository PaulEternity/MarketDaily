package com.pauleternity.marketdaily.client.news;

import java.time.Instant;
import java.util.List;

/** 统一新闻来源的采集契约。 */
public interface NewsFeed {

    /** 返回用于采集状态和日志的来源名。 */
    String source();

    /** 按本轮的目标窗口获取新闻摘要元数据。 */
    List<Item> fetch(int hours);

    /** publishedAt 为真实发布时间，seenAt 为来源首次收录时间，二者分别保留。 */
    record Item(String title, String url, String language, String summary, Instant publishedAt, Instant seenAt) {
    }
}
