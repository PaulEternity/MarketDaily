package com.pauleternity.marketdaily.service.news;

import com.pauleternity.marketdaily.client.news.NewsFeed;
import com.pauleternity.marketdaily.mapper.NewsMapper;
import com.pauleternity.marketdaily.model.news.NewsArticle;
import com.pauleternity.marketdaily.model.news.NewsCollectionResult;
import com.pauleternity.marketdaily.model.news.NewsItem;
import com.pauleternity.marketdaily.model.news.NewsMarket;
import com.pauleternity.marketdaily.model.news.NewsPage;
import com.pauleternity.marketdaily.model.news.NewsRow;
import com.pauleternity.marketdaily.model.news.NewsSourceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 编排新闻采集、筛选评分、入库及分页查询。 */
@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    // 时间线每个市场最多展示八个不同事件，控制首屏阅读量。
    private static final int HIGHLIGHT_LIMIT = 8;

    private final List<NewsFeed> feeds;
    private final NewsFilterService filter;
    private final NewsScoreService score;
    private final NewsDedupService dedup;
    private final NewsStore store;
    private final NewsMapper mapper;

    /** 注入新闻的两个来源及清洗、存储组件。 */
    NewsService(List<NewsFeed> feeds, NewsFilterService filter, NewsScoreService score, NewsDedupService dedup,
                NewsStore store, NewsMapper mapper) {
        this.feeds = feeds;
        this.filter = filter;
        this.score = score;
        this.dedup = dedup;
        this.store = store;
        this.mapper = mapper;
    }

    /** 平日读取最近 24 小时，周末使用 72 小时，同一实例串行归并事件。 */
    public synchronized NewsCollectionResult collect(Instant through) {
        int hours = switch (through.atZone(REPORT_ZONE).getDayOfWeek()) {
            case SATURDAY, SUNDAY -> 72;
            default -> 24;
        };
        Instant from = through.minus(Duration.ofHours(hours));
        Map<String, NewsSourceResult> sources = new LinkedHashMap<>();
        List<NewsArticle> articles = new ArrayList<>();
        int failures = 0;

        // 上游失败只影响该来源。清洗或数据库异常仍由接口按系统错误返回。
        for (NewsFeed feed : feeds) {
            List<NewsFeed.Item> items;
            try {
                items = feed.fetch(hours);
            } catch (RuntimeException ex) {
                failures++;
                sources.put(feed.source(), new NewsSourceResult("FAILED", 0, 0));
                log.warn("新闻来源采集失败，来源={}，异常类型={}", feed.source(), ex.getClass().getSimpleName());
                continue;
            }
            int accepted = 0;
            for (NewsFeed.Item item : items) {
                var candidate = filter.filter(item, from, through, "FED_RSS".equals(feed.source()));
                if (candidate == null) {
                    continue;
                }
                accepted++;
                articles.add(new NewsArticle(candidate.title(), candidate.normalized(), candidate.url(),
                        dedup.urlHash(candidate.url()), candidate.host(), candidate.language(), candidate.summary(),
                        candidate.publishedAt(), candidate.seenAt(), candidate.newsAt(), through,
                        null, score.score(candidate, through, hours), candidate.markets()));
            }
            sources.put(feed.source(), new NewsSourceResult(items.isEmpty() ? "EMPTY" : "SUCCESS",
                    items.size(), accepted));
        }

        // 同一轮按实际新闻时间从早到晚分配事件 ID，相同时间再按链接排序。
        articles.sort(Comparator.comparing(NewsArticle::newsAt).thenComparing(NewsArticle::url));
        int stored = store.save(articles, from, through);
        String status = failures == 0 ? "SUCCESS" : failures == feeds.size() ? "FAILED" : "PARTIAL";
        log.info("新闻采集结束，状态={}，窗口={}至{}，写入次数={}", status, from, through, stored);
        return new NewsCollectionResult(status, from, through, hours, stored, sources);
    }

    /** 按明确起止时间读取匹配新闻和总数。 */
    public NewsPage page(Instant from, Instant through, NewsMarket market, int page, int size, boolean recentFirst) {
        long total = mapper.count(from, through, market);
        // 用 long 计算页偏移，防止 int 页码乘以页大小时溢出。
        long offset = (page - 1L) * size;
        if (offset >= total) {
            return new NewsPage(from, through, page, size, total, List.of());
        }
        // 全部报道用时间排序，现有调用仍可以按重要度读取。
        List<NewsRow> rows = recentFirst ? mapper.recentPage(from, through, market, size, offset)
                : mapper.page(from, through, market, size, offset);
        return new NewsPage(from, through, page, size, total, rows.stream().map(this::toItem).toList());
    }

    /** 读取截至指定时刻的最近 24 小时事件代表报道。 */
    public List<NewsItem> highlights(Instant through, NewsMarket market) {
        return mapper.highlights(through.minus(Duration.ofHours(24)), through, market, HIGHLIGHT_LIMIT)
                .stream().map(this::toItem).toList();
    }

    /** 将共同的查询结果转换为带来源和市场标签的报道。 */
    private NewsItem toItem(NewsRow row) {
        // MyBatis 返回事件关联市场的逗号分隔值，缺少分类时返回空数组。
        List<NewsMarket> markets = row.markets() == null ? List.of() : Arrays.stream(row.markets().split(","))
                .map(NewsMarket::valueOf).toList();
        return new NewsItem(row.id(), row.title(), row.url(), row.source(), row.language(), row.summary(),
                row.publishedAt(), row.seenAt(), row.collectedAt(), row.eventHash(), row.score(), row.selected(), markets);
    }
}
