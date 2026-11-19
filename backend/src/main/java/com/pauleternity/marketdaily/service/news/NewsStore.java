package com.pauleternity.marketdaily.service.news;

import com.pauleternity.marketdaily.mapper.NewsMapper;
import com.pauleternity.marketdaily.model.news.NewsArticle;
import com.pauleternity.marketdaily.model.news.NewsEventMember;
import com.pauleternity.marketdaily.model.news.NewsMarket;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 管理同一次采集内新闻和市场关系的事务。 */
@Component
class NewsStore {

    private final NewsMapper mapper;
    private final NewsDedupService dedup;

    /** 复用数据库中已有的事件标识，新增的来源共享事件标识。 */
    NewsStore(NewsMapper mapper, NewsDedupService dedup) {
        this.mapper = mapper;
        this.dedup = dedup;
    }

    /** 入库按文章和市场关系整体提交，外部请求在事务外完成。 */
    @Transactional
    public int save(List<NewsArticle> articles, Instant from, Instant through) {
        if (articles.isEmpty()) {
            return 0;
        }
        // 比较当前窗口和窗口左侧 36 小时，处理同一事件跨窗口边界的情况。
        var candidates = new ArrayList<>(mapper.candidates(from.minus(NewsDedupService.EVENT_WINDOW), through));
        for (NewsArticle article : articles) {
            var existing = mapper.byUrl(article.urlHash());
            String eventHash = existing != null ? existing.eventHash() : matchEvent(article, candidates);
            mapper.save(new NewsArticle(article.title(), article.titleNormalized(), article.url(), article.urlHash(),
                    article.source(), article.language(), article.summary(), article.publishedAt(), article.seenAt(),
                    article.newsAt(), article.collectedAt(), eventHash, article.score(), article.markets()));
            var saved = mapper.byUrl(article.urlHash());
            for (NewsMarket market : article.markets()) {
                mapper.saveMarket(saved.id(), market);
            }
            if (existing == null) {
                candidates.add(saved);
            }
        }
        return articles.size();
    }

    /** 近似匹配只共享事件标识，独立来源和新闻 ID 都保留。 */
    private String matchEvent(NewsArticle article, List<NewsEventMember> candidates) {
        for (NewsEventMember other : candidates) {
            if (dedup.sameEvent(article.titleNormalized(), article.newsAt(), other.titleNormalized(), other.newsAt())) {
                return other.eventHash();
            }
        }
        return dedup.eventHash(article.titleNormalized(), article.newsAt(), article.urlHash());
    }
}
