package com.pauleternity.marketdaily.model.report;

import java.time.Instant;
import java.time.LocalDate;

/** 看板使用的自动日报状态，任一任务尚未执行时对应记录为空。 */
public record ReportScheduleStatus(boolean enabled, Task collection, Task generation) {

    /** 单次执行的摘要，字段顺序与查询一致。运行中没有完成时间，成功时原因为空。 */
    public record Task(LocalDate businessDate, int attempt, String status, Instant startedAt,
                       Instant finishedAt, String reason) {
    }
}
