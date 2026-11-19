package com.pauleternity.marketdaily.model.news;

import java.time.Instant;

/** 去重所需的持久化事件身份，SQL 列按构造器顺序读取。 */
public record NewsEventMember(long id, String titleNormalized, Instant newsAt, String eventHash) {
}
