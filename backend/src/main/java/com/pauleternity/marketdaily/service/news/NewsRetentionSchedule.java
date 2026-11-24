package com.pauleternity.marketdaily.service.news;

import com.pauleternity.marketdaily.mapper.NewsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 定期清理超过保留期限的新闻及其市场关系。 */
@Component
class NewsRetentionSchedule {

    private static final Logger log = LoggerFactory.getLogger(NewsRetentionSchedule.class);

    // 保留期按滚动的 7 × 24 小时计算。
    private static final Duration RETENTION = Duration.ofDays(7);

    private final NewsMapper mapper;

    /** 复用新闻 Mapper 执行数据库清理。 */
    NewsRetentionSchedule(NewsMapper mapper) {
        this.mapper = mapper;
    }

    /** 每天北京时间 03:30 清理过期新闻。 */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void purgeExpiredNews() {
        // 截止值对齐数据库的毫秒精度，等于截止时刻的新闻继续保留。
        Instant cutoff = Instant.now().minus(RETENTION).truncatedTo(ChronoUnit.MILLIS);
        // 单条删除语句通过外键级联同步清理市场关系。
        int deleted = mapper.deleteExpired(cutoff);
        log.info("新闻清理结束，截止时间={}，删除报道数={}", cutoff, deleted);
    }
}
