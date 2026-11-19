package com.pauleternity.marketdaily.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用模拟模型验证日报接口、MySQL 快照及重复触发行为。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "MARKET_ADMIN_KEY=report-test-admin")
@EnabledIfEnvironmentVariable(named = "MARKETDAILY_DB_TEST", matches = "true")
class ReportApiIntegrationTest {

    @Autowired private TestRestTemplate client;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @MockitoBean private ReportInput input;
    @MockitoBean private ReportModelClient model;

    /** 生成两次只调用一次模型，查询从 MySQL 恢复快照和引用。 */
    @Test
    void savesAndReusesReportWithCitations() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_test");
        // 独立测试库已存在的当日日报不应被测试覆盖。
        LocalDate date = LocalDate.now(ReportService.REPORT_ZONE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_report WHERE report_date=?", Long.class, date))
                .isZero();
        var article = new ReportContent.Article(7L, "测试新闻", "https://example.org/story", "example.org",
                null, Instant.EPOCH, null, List.of("GOLD"));
        when(input.snapshot(eq(date), any())).thenReturn(new ReportContent.Input(date, List.of(), List.of(article)));
        when(model.generate(any())).thenReturn(new ReportModelClient.Completion("report-test-only", json.readTree("""
                {"summary":"测试总览",
                 "GOLD":{"analysis":"有关贵金属的报道","watch":"关注收盘价","newsIds":[7]},
                 "NASDAQ":{"analysis":"暂无纳指数据","watch":"观察下个交易日","newsIds":[]},
                 "CRYPTO":{"analysis":"暂无比特币数据","watch":"观察下次报价","newsIds":[]}}
                """), 100, 80));
        var headers = new HttpHeaders();
        headers.set("X-Admin-Key", "report-test-admin");
        var request = new HttpEntity<Void>(headers);
        try {
            assertThat(client.postForEntity("/api/admin/reports/generate", null, JsonNode.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            var first = client.postForEntity("/api/admin/reports/generate", request, JsonNode.class);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(first.getBody().path("data").path("reused").asBoolean()).isFalse();
            var second = client.postForEntity("/api/admin/reports/generate", request, JsonNode.class);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.getBody().path("data").path("reused").asBoolean()).isTrue();
            verify(model).generate(any());

            var saved = client.getForObject("/api/reports/today", JsonNode.class).path("data").path("report");
            assertThat(saved.path("content").path("input").path("news").path(0).path("url").asText())
                    .isEqualTo("https://example.org/story");
            assertThat(saved.path("inputTokens").asInt()).isEqualTo(100);
            assertThat(client.getForObject("/api/reports/" + date, JsonNode.class).path("data").path("report"))
                    .isEqualTo(saved);
            assertThat(client.getForObject("/api/reports", JsonNode.class).path("data").path("items").path(0)
                    .path("date").asText()).isEqualTo(date.toString());
        } finally {
            jdbc.update("DELETE FROM daily_report WHERE report_date=? AND model_name='report-test-only'", date);
        }
    }
}
