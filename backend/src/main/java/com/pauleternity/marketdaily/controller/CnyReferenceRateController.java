package com.pauleternity.marketdaily.controller;

import com.pauleternity.marketdaily.model.ApiResult;
import com.pauleternity.marketdaily.service.market.CnyReferenceRateService;
import com.pauleternity.marketdaily.service.market.CnyReferenceRateService.ReferenceRate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 单独提供看板展示所需的人民币参考汇率。 */
@RestController
class CnyReferenceRateController {

    private final CnyReferenceRateService rates;

    /** 由汇率服务统一管理来源和缓存。 */
    CnyReferenceRateController(CnyReferenceRateService rates) {
        this.rates = rates;
    }

    /** 返回每美元人民币汇率，缺少有效数据时 data 为空。 */
    @GetMapping("/api/markets/exchange-rate")
    ApiResult<ReferenceRate> exchangeRate() {
        return ApiResult.ok(rates.latest(Instant.now()));
    }
}
