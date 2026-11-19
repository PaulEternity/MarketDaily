package com.pauleternity.marketdaily.controller;

import com.pauleternity.marketdaily.model.ApiResult;
import com.pauleternity.marketdaily.model.news.NewsCollectionResult;
import com.pauleternity.marketdaily.model.news.NewsMarket;
import com.pauleternity.marketdaily.model.news.NewsPage;
import com.pauleternity.marketdaily.service.news.NewsService;
import com.pauleternity.marketdaily.shared.AdminKey;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/** 新闻查询 API 和受管理密钥保护的手动采集入口。 */
@RestController
public class NewsController {

    // 保证 UTC 时间可以存入 MySQL DATETIME，留余量供默认窗口计算。
    private static final Instant MIN_DATE = Instant.parse("1000-01-02T00:00:00Z");
    private static final Instant MAX_DATE = Instant.parse("9999-12-30T00:00:00Z");

    private final NewsService news;
    private final AdminKey adminKey;

    /** 手动采集统一复用后端管理密钥。 */
    NewsController(NewsService news, AdminKey adminKey) {
        this.news = news;
        this.adminKey = adminKey;
    }

    /** 按市场和时间分页查询，默认读取最近 24 小时的存量新闻。 */
    @GetMapping("/api/news")
    ApiResult<NewsPage> page(@RequestParam(name = "market", required = false) NewsMarket market,
                                  @RequestParam(name = "from", required = false) Instant from,
                                  @RequestParam(name = "to", required = false) Instant to,
                                  @RequestParam(name = "page", defaultValue = "1") int page,
                                  @RequestParam(name = "size", defaultValue = "20") int size) {
        // 起止时间为 UTC 时间戳，限制单次查询最多跨 30 天。
        Instant through = to == null ? Instant.now() : to;
        if (through.isBefore(MIN_DATE) || through.isAfter(MAX_DATE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新闻结束时间超出范围");
        }
        Instant since = from == null ? through.minus(Duration.ofHours(24)) : from;
        Duration width = Duration.between(since, through);
        if (page < 1 || size < 1 || size > 100 || width.isNegative() || width.isZero()
                || width.compareTo(Duration.ofDays(30)) > 0 || since.isBefore(MIN_DATE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码、页大小或新闻时间窗口无效");
        }
        return ApiResult.ok(news.page(since, through, market, page, size));
    }

    /** 一次采集允许一个来源失败，响应包含实际窗口和逐来源状态。 */
    @PostMapping("/api/admin/news/collect")
    ResponseEntity<ApiResult<NewsCollectionResult>> collect(
            @RequestHeader(name = "X-Admin-Key", required = false) String suppliedKey) {
        adminKey.require(suppliedKey);
        var result = news.collect(Instant.now());
        if ("FAILED".equals(result.status())) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiResult<>(502, "新闻来源均采集失败", result));
        }
        return ResponseEntity.ok(ApiResult.ok(result));
    }

    /** 统一返回请求校验与授权错误。 */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResult<Void>> requestError(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode())
                .body(new ApiResult<>(failure.getStatusCode().value(), failure.getReason(), null));
    }

    /** 所有查询参数转换失败使用统一错误结构。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResult<Void>> invalidParameter() {
        return ResponseEntity.badRequest().body(new ApiResult<>(400, "市场、时间或分页参数格式无效", null));
    }
}
