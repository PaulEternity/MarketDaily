package com.pauleternity.marketdaily.controller;

import com.pauleternity.marketdaily.model.ApiResult;
import com.pauleternity.marketdaily.model.market.GoldSignals;
import com.pauleternity.marketdaily.model.market.GoldSignals.Bar;
import com.pauleternity.marketdaily.service.market.GoldSignalService;
import com.pauleternity.marketdaily.shared.AdminKey;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/** 公开的黄金走势查询，以及受既有管理密钥保护的 K 线导入。 */
@RestController
class GoldSignalController {

    private final GoldSignalService signals;
    private final AdminKey adminKey;

    /** 接入黄金统计服务和管理鉴权。 */
    GoldSignalController(GoldSignalService signals, AdminKey adminKey) {
        this.signals = signals;
        this.adminKey = adminKey;
    }

    /** 只读数据库，刷新看板不会调用第三方行情。 */
    @GetMapping("/api/markets/gold-signals")
    ApiResult<List<GoldSignals>> overview() {
        return ApiResult.ok(signals.overview(Instant.now()));
    }

    /** 仅接收由授权数据源导出的已收盘 K 线。 */
    @PostMapping("/api/admin/markets/gold-bars")
    ApiResult<Integer> importBars(@RequestHeader(name = "X-Admin-Key", required = false) String key,
                                  @RequestBody List<Bar> batch) {
        adminKey.require(key);
        return ApiResult.ok(signals.save(batch, Instant.now()));
    }

    /** 为已知管理请求错误返回一致的 API 结构。 */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResult<Void>> requestError(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode())
                .body(new ApiResult<>(failure.getStatusCode().value(), failure.getReason(), null));
    }
}
