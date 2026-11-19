package com.pauleternity.marketdaily.model.report;

/** reused 表示直接复用了同一业务日的已保存结果。 */
public record ReportGeneration(boolean reused, ReportDetail report) {
}
