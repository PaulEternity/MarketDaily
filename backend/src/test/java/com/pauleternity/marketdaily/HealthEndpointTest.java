package com.pauleternity.marketdaily;

import com.fasterxml.jackson.databind.JsonNode;
import com.pauleternity.marketdaily.mapper.GoldSignalMapper;
import com.pauleternity.marketdaily.mapper.MarketPriceMapper;
import com.pauleternity.marketdaily.mapper.NewsMapper;
import com.pauleternity.marketdaily.mapper.ReportMapper;
import com.pauleternity.marketdaily.mapper.TaskExecutionMapper;
import com.pauleternity.marketdaily.model.report.ReportScheduleStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 在不启动数据库的情况下验证 HTTP 路由及响应结构。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
)
class HealthEndpointTest {

    @Autowired
    private TestRestTemplate client;

    // 此冒烟测试不启动数据库，为行情服务提供 Mapper 占位。
    @MockitoBean
    private MarketPriceMapper marketPrices;

    @MockitoBean
    private GoldSignalMapper goldSignals;

    // 数据库被禁用时，替换新闻模块依赖的 Mapper。
    @MockitoBean
    private NewsMapper news;

    // 健康检查本身不读取日报库。
    @MockitoBean
    private ReportMapper reports;

    // 模拟自动任务的最新记录，让只读接口可以脱离数据库验证。
    @MockitoBean
    private TaskExecutionMapper tasks;

    @Test
    void reportsUp() {
        var response = client.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
    }

    /** 固定路径优先于日期路由，返回失败原因和仍未执行的生成任务。 */
    @Test
    void servesLatestScheduleStatus() {
        var started = Instant.parse("2026-11-26T00:15:00Z");
        when(tasks.latestExecution("DAILY_INPUT")).thenReturn(new ReportScheduleStatus.Task(
                LocalDate.of(2026, 11, 26), 1, "PARTIAL", started, started.plusSeconds(5), "新闻采集失败：JIN10_BTC"));

        var response = client.getForEntity("/api/reports/schedule-status", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().at("/data/collection/status").asText()).isEqualTo("PARTIAL");
        assertThat(response.getBody().at("/data/collection/reason").asText()).isEqualTo("新闻采集失败：JIN10_BTC");
        assertThat(response.getBody().at("/data/generation").isNull()).isTrue();
    }
}
