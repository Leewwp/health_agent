package com.diet.model;

/** 新健康接口统一错误结构。 */
public record ApiErrorResponse(String code, String message, String requestId, String traceId) {
}
