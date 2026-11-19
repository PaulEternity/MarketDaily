package com.pauleternity.marketdaily.shared;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeoutException;

/** 行情与新闻请求共用的超时、重试和响应解析。 */
@Component
public class SourceHttp {

    // 一次请求及其响应体的总时间上限，单位为秒。
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient client;
    private final ObjectReader reader;

    /** 保留行情 JSON 的十进制精度，避免经由 double 转换价格。 */
    public SourceHttp(WebClient.Builder builder, ObjectMapper mapper) {
        // 响应体最大 1 MiB，足以读取新闻列表，同时限制上游异常返回的大小。
        this.client = builder.clone().codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024 * 1024)).build();
        this.reader = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /** 供各数据来源构造 GET 请求。 */
    public WebClient.RequestHeadersUriSpec<?> get() {
        return client.get();
    }

    /** 将 JSON 响应解析为树，保留外部数值精度。 */
    public JsonNode read(WebClient.RequestHeadersSpec<?> request) {
        try {
            return reader.readTree(readBody(request));
        } catch (IOException ex) {
            throw new IllegalStateException("数据源 JSON 无法解析");
        }
    }

    /** 读取 JSON 或 RSS 原文，仅重试传输异常、超时和 5xx，最多两次。 */
    public String readBody(WebClient.RequestHeadersSpec<?> request) {
        // 先收完响应体再结束超时计时，避免上游慢速响应长期占用采集任务。
        String body = request.retrieve().bodyToMono(String.class)
                .timeout(REQUEST_TIMEOUT)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(250)).filter(SourceHttp::retryable))
                .block();
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("数据源响应为空");
        }
        return body;
    }

    /** 限流和参数错误留给下一次采集处理。 */
    private static boolean retryable(Throwable failure) {
        return failure instanceof TimeoutException || failure instanceof WebClientRequestException
                || failure instanceof WebClientResponseException response && response.getStatusCode().is5xxServerError();
    }

    /** 兼容 FRED 的字符串数值，空值与非法值保持为空。 */
    public static BigDecimal decimal(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.textValue());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** 解析来源提供的时间戳，不用采集时间替代无效时间。 */
    public static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText(""));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
