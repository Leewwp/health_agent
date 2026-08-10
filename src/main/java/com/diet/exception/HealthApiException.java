package com.diet.exception;

/**
 * 健康接口业务异常（规格 6.5 统一错误结构 code 维度）：
 * 参数错误 / 资源不存在 / 风险拒绝 / 版本或幂等冲突 / 身份无效 / 服务异常。
 */
public class HealthApiException extends RuntimeException {

    public static final String CODE_BAD_REQUEST = "BAD_REQUEST";
    public static final String CODE_IDENTITY_INVALID = "IDENTITY_INVALID";
    public static final String CODE_NOT_FOUND = "NOT_FOUND";
    public static final String CODE_RISK_BLOCKED = "RISK_BLOCKED";
    public static final String CODE_CONFLICT = "CONFLICT";
    public static final String CODE_SERVICE_ERROR = "SERVICE_ERROR";

    private final String code;

    public HealthApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
