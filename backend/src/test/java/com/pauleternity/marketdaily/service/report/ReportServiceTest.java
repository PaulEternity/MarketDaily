package com.pauleternity.marketdaily.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pauleternity.marketdaily.client.report.ReportModelClient;
import com.pauleternity.marketdaily.mapper.ReportMapper;
import com.pauleternity.marketdaily.model.report.ReportContent;
import com.pauleternity.marketdaily.model.report.StoredReport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证日报不会引用范围外报道，也不会重复调用模型。 */
class ReportServiceTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    /** 不存在或不属于该市场的新闻均不可写进日报。 */
    @Test
    void validatesNewsAgainstTheExactInputSnapshot() throws Exception {
        var article = new ReportContent.Article(7L, "报道标题", "https://example.org/article", "example.org",
                null, null, Instant.EPOCH, List.of("GOLD"));
        var input = new ReportContent.Input(LocalDate.of(2026, 11, 19), List.of(), List.of(article));
        String response = """
                {"summary":"市场总览",
                  "GOLD":{"analysis":"贵金属分析","watch":"观察金价","newsIds":[7]},
                  "NASDAQ":{"analysis":"纳指分析","watch":"观察指数","newsIds":[]},
                  "CRYPTO":{"analysis":"比特币分析","watch":"观察价格","newsIds":[]}
                }
                """;
        var result = ReportService.validate(json.readTree(response), input);
        assertThat(result.sections().getFirst().newsIds()).containsExactly(7L);
        assertThatThrownBy(() -> ReportService.validate(json.readTree(response.replace("[7]", "[8]")), input))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("引用无效");
        assertThatThrownBy(() -> ReportService.validate(json.readTree(response
                        .replace("\"newsIds\":[7]", "\"newsIds\":[]")
                        .replace("\"watch\":\"观察指数\",\"newsIds\":[]", "\"watch\":\"观察指数\",\"newsIds\":[7]")), input))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("NASDAQ 的新闻引用无效");
        assertThatThrownBy(() -> ReportService.validate(json.readTree(response.replace("[7]", "[\"7\"]")), input))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GOLD.newsIds 只能包含整数");
        assertThatThrownBy(() -> ReportService.validate(json.readTree(response.replace("\"CRYPTO\"", "\"other\"")), input))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("日报缺少 CRYPTO 板块对象");
    }

    /** 模型输出被截断或不是 JSON 时，提示具体原因且不回显正文。 */
    @Test
    void distinguishesTruncatedOutputFromInvalidJson() {
        var input = new ReportContent.Input(LocalDate.of(2026, 11, 19), List.of(), List.of());
        var truncated = modelResponse("""
                {"choices":[{"finish_reason":"length","message":{"content":"上游正文"}}]}
                """);
        var malformed = modelResponse("""
                {"choices":[{"finish_reason":"stop","message":{"content":"上游正文"}}]}
                """);
        assertThatThrownBy(() -> truncated.generate(input)).hasMessageContaining("被截断").hasMessageNotContaining("上游正文");
        assertThatThrownBy(() -> malformed.generate(input)).hasMessageContaining("完整的 JSON").hasMessageNotContaining("上游正文");
    }

    /** 测试只注入模拟 HTTP 响应，不访问硅基流动。 */
    private ReportModelClient modelResponse(String body) {
        var builder = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse
                .create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build()));
        return new ReportModelClient(builder, json, "https://api.siliconflow.cn/v1", "simulated-key", "test-model");
    }

    /** 已生成的业务日不再读取新证据或调用模型。 */
    @Test
    void reusesSavedReportBeforeCallingModel() throws Exception {
        var mapper = mock(ReportMapper.class);
        var input = mock(ReportInput.class);
        var model = mock(ReportModelClient.class);
        LocalDate date = LocalDate.now(ReportService.REPORT_ZONE);
        var document = new ReportContent(new ReportContent.Analysis("已保存", List.of()),
                new ReportContent.Input(date, List.of(), List.of()));
        when(mapper.onDate(any())).thenReturn(new StoredReport(date, "已保存",
                json.writeValueAsString(document), "unused-test-hash", "test-model", "daily-v1",
                10, 20, Instant.EPOCH));

        var result = new ReportService(mapper, input, model, json).generateToday();

        assertThat(result.reused()).isTrue();
        assertThat(result.report().content().analysis().summary()).isEqualTo("已保存");
        verify(mapper).onDate(date);
        verifyNoInteractions(input, model);
    }
}
