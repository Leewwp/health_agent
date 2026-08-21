package com.diet.exception;

import com.diet.constants.DietConstants;
import com.diet.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只为新健康 Controller 提供统一错误结构（规格 6.5），保持旧饮食响应兼容。 */
@RestControllerAdvice(basePackages = "com.diet.controller.health")
public class HealthApiExceptionHandler {

    @ExceptionHandler(HealthApiException.class)
    public ResponseEntity<ApiErrorResponse> handleHealthApiException(HealthApiException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case HealthApiException.CODE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case HealthApiException.CODE_RISK_BLOCKED, HealthApiException.CODE_CONFLICT,
                    HealthApiException.CODE_PLAN_VERSION_CONFLICT, HealthApiException.CODE_PLAN_STATE_CONFLICT,
                    HealthApiException.CODE_PLAN_TIME_CONFLICT, HealthApiException.CODE_PLAN_RESOURCE_INVALID,
                    HealthApiException.CODE_PLAN_IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case HealthApiException.CODE_IDENTITY_INVALID -> HttpStatus.UNAUTHORIZED;
            case HealthApiException.CODE_RESOURCE_MODE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case HealthApiException.CODE_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(error.code(), error.getMessage(), requestId(request), null));
    }

    @ExceptionHandler(DietException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleDietException(DietException error, HttpServletRequest request) {
        return new ApiErrorResponse("BAD_REQUEST", error.getMessage(), requestId(request), null);
    }

    /**
     * 请求体不可读（非法 JSON、未知枚举等）按参数错误返回（62 号票：
     * 档案风险条件列表出现未知枚举必须被干净拒绝，不能落入 500）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleUnreadableBody(HttpMessageNotReadableException error, HttpServletRequest request) {
        return new ApiErrorResponse("BAD_REQUEST", "请求体格式错误", requestId(request), null);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handleException(Exception error, HttpServletRequest request) {
        return new ApiErrorResponse("SERVICE_ERROR", "服务异常", requestId(request), null);
    }

    private String requestId(HttpServletRequest request) {
        return request.getHeader(DietConstants.REQUEST_ID);
    }
}
