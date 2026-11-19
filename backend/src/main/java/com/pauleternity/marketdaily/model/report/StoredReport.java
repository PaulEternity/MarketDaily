package com.pauleternity.marketdaily.model.report;

import java.time.Instant;
import java.time.LocalDate;

/** 数据库中的日报详情，字段顺序与 SELECT 一致。 */
public record StoredReport(LocalDate date, String summary, String content, String inputHash, String model,
              String promptVersion, Integer inputTokens, Integer outputTokens, Instant generatedAt) {
}
