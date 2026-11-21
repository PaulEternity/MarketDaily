package com.pauleternity.marketdaily.service.report;

import com.pauleternity.marketdaily.mapper.TaskExecutionMapper;
import com.pauleternity.marketdaily.model.report.ReportGeneration;
import com.pauleternity.marketdaily.service.market.MarketService;
import com.pauleternity.marketdaily.service.news.NewsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.pauleternity.marketdaily.service.report.ReportService.REPORT_ZONE;

@Component
@ConditionalOnProperty(name = "marketdaily.report.schedule-enabled", havingValue = "true")
public class DailyReportSchedule {
    private static final Logger log = LoggerFactory.getLogger(DailyReportSchedule.class);
    private static final String INPUT = "DAILY_INPUT";
    private static final String REPORT = "DAILY_REPORT";
    private static final LocalTime GENERATE_AT = LocalTime.of(8, 30);
    private static final LocalTime RETRY_AT = LocalTime.of(9, 0);

    private final MarketService markets;
    private final NewsService news;
    private final ReportService reports;
    private final TaskExecutionMapper tasks;
    private final TaskScheduler scheduler;

    DailyReportSchedule(MarketService markets, NewsService news, ReportService reports,
                        TaskExecutionMapper tasks, TaskScheduler scheduler) {
        this.markets = markets;
        this.news = news;
        this.reports = reports;
        this.tasks = tasks;
        this.scheduler = scheduler;
    }

    @Scheduled(cron = "0 15 8 * * *", zone = "Asia/Shanghai")
    synchronized void collectMorning() {
        Instant now = scheduler.getClock().instant();
        collect(now.atZone(REPORT_ZONE).toLocalDate(), now);
    }

    @Scheduled(cron = "0 30 8 * * *", zone = "Asia/Shanghai")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")
    void generateMorning() {
        generateIfDue(scheduler.getClock().instant());
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverAfterStartup() {
        scheduler.schedule(() -> {
            synchronized (this) {
                Instant now = scheduler.getClock().instant();
                tasks.markInterrupted(now);
                generateIfDue(now);
            }
        }, scheduler.getClock().instant());
    }

    synchronized void generateIfDue(Instant now) {
        var local = now.atZone(REPORT_ZONE);
        LocalDate date = local.toLocalDate();
        LocalTime time = local.toLocalTime();
        if (time.isBefore(GENERATE_AT) || reports.onDate(date) != null) {
            return;
        }
        int previousAttempts = tasks.lastAttempt(REPORT, date);
        int allowedAttempts = time.isBefore(RETRY_AT) ? 1 : 2;
        if (previousAttempts >= allowedAttempts) {
            return;
        }

        String traceId = start(REPORT, date, previousAttempts + 1);
        String stage = "准备采集";
        ReportGeneration result;
        try {
            String inputStatus = tasks.latestStatus(INPUT, date);
            if (!"SUCCESS".equals(inputStatus) && !"PARTIAL".equals(inputStatus)) {
                collect(date, now);
            }
            // 日期可能在长时间等待或采集后变化，留给新业务日自己的调度处理。
            if (!date.equals(scheduler.getClock().instant().atZone(REPORT_ZONE).toLocalDate())) {
                tasks.finish(traceId, "INTERRUPTED", "日报生成前业务日期已变更", Instant.now());
                return;
            }
            stage = "生成或保存日报";
            result = reports.generateToday();
        } catch (RuntimeException ex) {
            String reason = ex instanceof ResponseStatusException status ? status.getReason()
                    : ex.getClass().getSimpleName();
            String error = stage + "失败：" + reason;
            tasks.finish(traceId, "FAILED", error, Instant.now());
            log.warn("日报任务失败，日期={}，traceId={}，原因={}", date, traceId, error);
            return;
        }
        tasks.finish(traceId, "SUCCESS", null, Instant.now());
        log.info("日报任务完成，日期={}，traceId={}，复用={}，输入Token={}，输出Token={}", date, traceId,
                result.reused(), result.report().inputTokens(), result.report().outputTokens());
    }

    private void collect(LocalDate date, Instant now) {
        String traceId = start(INPUT, date, tasks.lastAttempt(INPUT, date) + 1);
        var problems = new ArrayList<String>();
        int completed = 0;
        try {
            var quotes = markets.collect(date);
            String unavailable = quotes.stream()
                    .filter(quote -> !"AVAILABLE".equals(quote.collectionStatus()))
                    .map(quote -> quote.symbol()).collect(Collectors.joining(", "));
            if (!unavailable.isEmpty()) {
                problems.add("行情未更新：" + unavailable);
            }
            if (quotes.stream().anyMatch(quote -> "AVAILABLE".equals(quote.collectionStatus()))) {
                completed++;
            }
        } catch (RuntimeException ex) {
            problems.add("行情采集失败：" + ex.getClass().getSimpleName());
        }
        try {
            var result = news.collect(now);
            String failedSources = result.sources().entrySet().stream()
                    .filter(entry -> "FAILED".equals(entry.getValue().status()))
                    .map(entry -> entry.getKey()).collect(Collectors.joining(", "));
            if (!failedSources.isEmpty()) {
                problems.add("新闻采集失败：" + failedSources);
            }
            if (!"FAILED".equals(result.status())) {
                completed++;
            }
        } catch (RuntimeException ex) {
            problems.add("新闻采集失败：" + ex.getClass().getSimpleName());
        }
        String status = completed == 0 ? "FAILED" : problems.isEmpty() ? "SUCCESS" : "PARTIAL";
        String error = problems.isEmpty() ? null : String.join("；", problems);
        tasks.finish(traceId, status, error, Instant.now());
        log.info("日报采集结束，日期={}，traceId={}，状态={}，原因={}", date, traceId, status, error);
    }

    private String start(String type, LocalDate date, int attempt) {
        String traceId = UUID.randomUUID().toString();
        tasks.start(type, date, attempt, traceId, Instant.now());
        return traceId;
    }
}
