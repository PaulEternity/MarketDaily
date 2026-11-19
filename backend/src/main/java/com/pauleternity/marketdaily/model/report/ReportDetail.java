package com.pauleternity.marketdaily.model.report;

import java.time.Instant;
import java.time.LocalDate;

/** 详情含输入快照，历史记录不会随行情或新闻变化。 */
public record ReportDetail(LocalDate date, String model, String promptVersion, Integer inputTokens,
              Integer outputTokens, Instant generatedAt, ReportContent content) {
}
