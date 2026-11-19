package com.pauleternity.marketdaily.model.news;

import java.time.Instant;
import java.util.Map;

/** 采集结果包含实际 UTC 窗口和成功来源。 */
public record NewsCollectionResult(String status, Instant from, Instant through, int windowHours,
                        int saveAttempts, Map<String, NewsSourceResult> sources) {
}
