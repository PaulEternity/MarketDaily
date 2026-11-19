package com.pauleternity.marketdaily.model.news;

/** 单一来源返回和通过筛选的条目数。 */
public record NewsSourceResult(String status, int fetched, int accepted) {
}
