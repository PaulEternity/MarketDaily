package com.pauleternity.marketdaily.market;

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

/** 行情请求的统一超时、重试和 JSON 数值解析。 */
@Component
class MarketHttp {

    // 一次请求及其响应体的总时间上限，单位为秒。
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient client;
    private final ObjectReader reader;

    /** 保留行情 JSON 的十进制精度，避免经由 double 转换价格。 */
    MarketHttp(WebClient.Builder builder, ObjectMapper mapper) {
        this.client = builder.clone().build();
        this.reader = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /** 供各 Provider 构造固定来源的 GET 请求。 */
    WebClient.RequestHeadersUriSpec<?> get() {
        return client.get();
    }

    /** 仅重试传输异常、超时和 5xx，最多两次。 */
    JsonNode read(WebClient.RequestHeadersSpec<?> request) {
        // 先收完响应体再结束超时计时，避免上游慢速响应长期占用采集任务。
        String body = request.retrieve().bodyToMono(String.class)
                .timeout(REQUEST_TIMEOUT)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(250)).filter(MarketHttp::retryable))
                .block();
        if (body == null) {
            throw new IllegalStateException("行情响应为空");
        }
        try {
            return reader.readTree(body);
        } catch (IOException ex) {
            throw new IllegalStateException("行情响应无法解析");
        }
    }

    /** 限流和参数错误留给下一次采集处理。 */
    private static boolean retryable(Throwable failure) {
        return failure instanceof TimeoutException || failure instanceof WebClientRequestException
                || failure instanceof WebClientResponseException response && response.getStatusCode().is5xxServerError();
    }

    /** 兼容 FRED 的字符串数值，空值与非法值保持为空。 */
    static BigDecimal decimal(JsonNode value) {
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
    static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText(""));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
