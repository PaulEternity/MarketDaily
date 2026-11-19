package com.pauleternity.marketdaily.news;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

/** 用相关性、事件重要性、来源可信度和时效对新闻评分。 */
@Component
class NewsScoreService {

    private static final Set<String> MAJOR = Set.of("rate decision", "rate cut", "rate hike", "fomc",
            "inflation report", "cpi", "nonfarm payrolls", "jobs report", "earnings", "etf approval",
            "sec approves", "crypto regulation", "利率决议", "非农", "财报", "通胀数据", "监管");
    private static final Set<String> OFFICIAL = Set.of("federalreserve.gov", "bls.gov", "bea.gov", "sec.gov");
    private static final Set<String> ESTABLISHED = Set.of("reuters.com", "apnews.com", "bloomberg.com",
            "ft.com", "wsj.com", "cnbc.com");

    // 四项权重为整数百分比，顺序依次为相关性、重要性、可信度和时效。
    private final int[] weights;

    /** 默认权重为 40、30、20、10，可由部署环境覆盖。 */
    NewsScoreService(@Value("${NEWS_SCORE_WEIGHTS:40,30,20,10}") String configured) {
        this.weights = Arrays.stream(configured.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        if (weights.length != 4 || Arrays.stream(weights).anyMatch(weight -> weight < 0 || weight > 100)
                || Arrays.stream(weights).sum() != 100) {
            throw new IllegalArgumentException("NEWS_SCORE_WEIGHTS 须为四个非负整数且总和为 100");
        }
    }

    /** 使用实际时间窗口衰减分数，结果为 0 到 100，精度为两位小数。 */
    BigDecimal score(NewsFilterService.Candidate item, Instant through, int hours) {
        int relevance = Math.min(100, 50 + item.matches() * 15);
        int importance = NewsFilterService.matchesAny(item.normalized(), MAJOR) ? 100 : 55;
        int credibility = domainMatches(item.host(), OFFICIAL) ? 100
                : domainMatches(item.host(), ESTABLISHED) ? 85 : 50;
        long ageMinutes = Duration.between(item.newsAt(), through).toMinutes();
        int timeliness = (int) Math.max(0, 100 - ageMinutes * 100 / (hours * 60L));
        return BigDecimal.valueOf((long) relevance * weights[0] + (long) importance * weights[1]
                + (long) credibility * weights[2] + (long) timeliness * weights[3], 2);
    }

    /** 只信任指定域名或其下级域名。 */
    private boolean domainMatches(String host, Set<String> domains) {
        return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }
}
