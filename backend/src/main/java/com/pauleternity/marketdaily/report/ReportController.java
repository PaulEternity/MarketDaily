package com.pauleternity.marketdaily.report;

import com.pauleternity.marketdaily.shared.AdminKey;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;

/** 只读日报接口和需要管理员密钥的生成入口。 */
@RestController
class ReportController {

    private final ReportService reports;
    private final AdminKey adminKey;

    /** 分享采集接口已有的管理员授权逻辑。 */
    ReportController(ReportService reports, AdminKey adminKey) {
        this.reports = reports;
        this.adminKey = adminKey;
    }

    /** 读取北京时间今日日报，尚未生成时 report 为空。 */
    @GetMapping("/api/reports/today")
    Result<Lookup> today() {
        return byDate(LocalDate.now(ReportService.REPORT_ZONE));
    }

    /** 读取指定日期已存结果。 */
    @GetMapping("/api/reports/{date}")
    Result<Lookup> byDate(@PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date.getYear() < 1000 || date.getYear() > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日报日期无效");
        }
        return new Result<>(0, "成功", new Lookup(reports.onDate(date)));
    }

    /** 分页浏览往期日报摘要。 */
    @GetMapping("/api/reports")
    Result<ReportService.Page> history(@RequestParam(name = "page", defaultValue = "1") int page,
                                      @RequestParam(name = "size", defaultValue = "10") int size) {
        if (page < 1 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日报分页参数无效");
        }
        return new Result<>(0, "成功", reports.history(page, size));
    }

    /** 手动生成今日日报，不接受任意提示词或前端选择的模型。 */
    @PostMapping("/api/admin/reports/generate")
    Result<ReportService.Generation> generate(@RequestHeader(name = "X-Admin-Key", required = false) String key) {
        adminKey.require(key);
        return new Result<>(0, "成功", reports.generateToday());
    }

    /** 保持业务异常与同一 API 响应结构一致。 */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Result<Void>> requestError(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(new Result<>(ex.getStatusCode().value(), ex.getReason(), null));
    }

    /** 返回清晰的参数格式错误。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Result<Void>> invalidParameter() {
        return ResponseEntity.badRequest().body(new Result<>(400, "日报日期或分页参数格式无效", null));
    }

    /** 详情不存在时仍有响应结构，前端据此展示待生成。 */
    record Lookup(ReportService.Detail report) {
    }

    /** 与现有接口一致的响应结构。 */
    record Result<T>(int code, String message, T data) {
    }
}
