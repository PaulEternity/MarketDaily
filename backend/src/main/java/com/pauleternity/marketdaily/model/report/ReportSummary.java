package com.pauleternity.marketdaily.model.report;

import java.time.Instant;
import java.time.LocalDate;

/** 历史列表摘要。 */
public record ReportSummary(LocalDate date, String summary, String model, Instant generatedAt) {
}
