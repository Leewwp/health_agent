package com.diet.exception;

import com.diet.constants.DietConstants;
import com.diet.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只为新健康 Controller 提供统一错误结构，保持旧饮食响应兼容。 */
@RestControllerAdvice(basePackages = "com.diet.controller.health")
public class HealthApiExceptionHandler {

    @ExceptionHandler(DietException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleDietException(DietException error, HttpServletRequest request) {
        return new ApiErrorResponse("BAD_REQUEST", error.getMessage(), requestId(request), null);
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
