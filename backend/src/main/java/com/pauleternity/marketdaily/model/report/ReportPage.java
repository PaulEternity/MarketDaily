package com.pauleternity.marketdaily.model.report;

import java.util.List;

/** 分页查询结果。 */
public record ReportPage(int page, int size, long total, List<ReportSummary> items) {
}
