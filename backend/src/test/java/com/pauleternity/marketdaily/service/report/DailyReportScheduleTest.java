package com.pauleternity.marketdaily.service.report;

import com.pauleternity.marketdaily.mapper.TaskExecutionMapper;
import com.pauleternity.marketdaily.model.news.NewsCollectionResult;
import com.pauleternity.marketdaily.model.news.NewsSourceResult;
import com.pauleternity.marketdaily.model.report.ReportDetail;
import com.pauleternity.marketdaily.model.report.ReportGeneration;
import com.pauleternity.marketdaily.service.market.MarketService;
import com.pauleternity.marketdaily.service.news.NewsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyReportScheduleTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 21);
    private static final Instant MORNING = Instant.parse("2026-11-21T00:30:00Z");
    private static final Instant RETRY = Instant.parse("2026-11-21T01:00:00Z");
    private static final ReportDetail DETAIL = new ReportDetail(DATE, "test", "test", 12, 6, MORNING, null);

    private final MarketService markets = mock(MarketService.class);
    private final NewsService news = mock(NewsService.class);
    private final ReportService reports = mock(ReportService.class);
    private final TaskExecutionMapper tasks = mock(TaskExecutionMapper.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final DailyReportSchedule schedule = new DailyReportSchedule(markets, news, reports, tasks, scheduler);

    @Test
    void startupCollectsMissedInputsAndStillGeneratesWithPartialData() {
        when(scheduler.getClock()).thenReturn(Clock.fixed(MORNING, ZoneOffset.UTC));
        when(markets.collect(DATE)).thenThrow(new IllegalStateException("do not log this"));
        when(news.collect(MORNING)).thenReturn(new NewsCollectionResult("SUCCESS", MORNING, MORNING, 24, 0,
                Map.of("feed", new NewsSourceResult("EMPTY", 0, 0))));
        when(reports.generateToday()).thenReturn(new ReportGeneration(false, DETAIL));

        schedule.recoverAfterStartup();
        var action = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(action.capture(), eq(MORNING));
        action.getValue().run();

        var order = inOrder(tasks, markets, news, reports);
        order.verify(tasks).markInterrupted(MORNING);
        order.verify(markets).collect(DATE);
        order.verify(news).collect(MORNING);
        order.verify(tasks).finish(anyString(), eq("PARTIAL"), eq("行情采集失败：IllegalStateException"), any());
        order.verify(reports).generateToday();
        order.verify(tasks).finish(anyString(), eq("SUCCESS"), isNull(), any());
    }

    @Test
    void failedGenerationCanRetryOnlyAfterRetryTimeAndIsBounded() {
        when(scheduler.getClock()).thenReturn(Clock.fixed(RETRY, ZoneOffset.UTC));
        when(tasks.lastAttempt("DAILY_REPORT", DATE)).thenReturn(0, 1, 1, 2);
        when(tasks.latestStatus("DAILY_INPUT", DATE)).thenReturn("SUCCESS");
        when(reports.generateToday())
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型请求失败"))
                .thenReturn(new ReportGeneration(false, DETAIL));

        schedule.generateIfDue(MORNING);
        schedule.generateIfDue(MORNING.plusSeconds(60));
        schedule.generateIfDue(RETRY);
        schedule.generateIfDue(RETRY.plusSeconds(60));

        verify(reports, times(2)).generateToday();
        verify(tasks).finish(anyString(), eq("FAILED"), eq("生成或保存日报失败：模型请求失败"), any());
        verify(tasks).start(eq("DAILY_REPORT"), eq(DATE), eq(2), anyString(), any());
        verify(tasks).finish(anyString(), eq("SUCCESS"), isNull(), any());
        verifyNoInteractions(markets, news);
    }

    @Test
    void skipsBeforeDueAndWhenReportAlreadyExists() {
        when(reports.onDate(DATE)).thenReturn(DETAIL);
        schedule.generateIfDue(MORNING.minusSeconds(1));
        verifyNoInteractions(reports);

        schedule.generateIfDue(RETRY);
        verify(reports).onDate(DATE);
        verifyNoMoreInteractions(reports);
        verifyNoInteractions(tasks, markets, news);
    }
}
