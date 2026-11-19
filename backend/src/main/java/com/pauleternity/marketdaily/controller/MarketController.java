package com.pauleternity.marketdaily.controller;

import com.pauleternity.marketdaily.model.ApiResult;
import com.pauleternity.marketdaily.model.market.HourlyMarketHistory;
import com.pauleternity.marketdaily.model.market.LatestQuote;
import com.pauleternity.marketdaily.model.market.MarketAsset;
import com.pauleternity.marketdaily.model.market.MarketHistory;
import com.pauleternity.marketdaily.service.market.MarketService;
import com.pauleternity.marketdaily.shared.AdminKey;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 行情只读 API 和受管理密钥保护的手动采集入口。 */
@RestController
public class MarketController {

    private final MarketService markets;
    private final AdminKey adminKey;

    /** 管理密钥为空时禁用手动采集。 */
    MarketController(MarketService markets, AdminKey adminKey) {
        this.markets = markets;
        this.adminKey = adminKey;
    }

    /** 只读数据库，页面刷新不会访问行情来源。 */
    @GetMapping("/api/markets/latest")
    ApiResult<List<LatestQuote>> latest() {
        return ApiResult.ok(markets.latest(LocalDate.now(MarketAsset.REPORT_ZONE)));
    }

    /** 按资产查询最近 7 或 30 个自然日的真实行情。 */
    @GetMapping("/api/markets/{symbol}/history")
    ApiResult<MarketHistory> history(@PathVariable("symbol") MarketAsset symbol,
                                     @RequestParam(name = "days", defaultValue = "7") int days) {
        if (days != 7 && days != 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days 仅支持 7 或 30");
        }
        return ApiResult.ok(markets.history(symbol, days, LocalDate.now(MarketAsset.REPORT_ZONE)));
    }

    /** 读取支持小时趋势的资产最近 24 小时已存报价。 */
    @GetMapping("/api/markets/{symbol}/hourly")
    ApiResult<HourlyMarketHistory> hourly(@PathVariable("symbol") MarketAsset symbol) {
        if (!symbol.supportsHourly()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该资产仅提供日行情");
        }
        return ApiResult.ok(markets.hourlyHistory(symbol, Instant.now()));
    }

    /** 管理请求触发一轮采集，并返回各资产的入库结果。 */
    @PostMapping("/api/admin/markets/collect")
    ApiResult<List<LatestQuote>> collect(
            @RequestHeader(name = "X-Admin-Key", required = false) String suppliedKey) {
        adminKey.require(suppliedKey);
        return ApiResult.ok(markets.collect(LocalDate.now(MarketAsset.REPORT_ZONE)));
    }

    /** 为已知请求错误返回相同的响应结构。 */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResult<Void>> requestError(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode())
                .body(new ApiResult<>(failure.getStatusCode().value(), failure.getReason(), null));
    }

    /** 指出无效资产标识或无法转换的窗口参数。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResult<Void>> invalidParameter() {
        return ResponseEntity.badRequest().body(new ApiResult<>(400, "资产标识或 days 参数无效", null));
    }
}
