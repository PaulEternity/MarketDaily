package com.pauleternity.marketdaily.model;

/** 后端 HTTP 接口的通用响应，data 保存成功结果或错误附带信息。 */
public record ApiResult<T>(int code, String message, T data) {

    /** 封装成功返回的业务数据。 */
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "成功", data);
    }
}
