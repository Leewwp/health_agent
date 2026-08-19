package com.diet.agent.contract;

/**
 * Agent 契约失败的统一分类。
 * <p>
 * 所有失败都必须映射到这些类型并产生确定性降级，不得用空 catch 丢失原因。
 */
public enum AgentFailureType {
    /** 单次调用超时。 */
    TIMEOUT,
    /** 上游不可用（网络、鉴权、限流、服务端错误）。 */
    UPSTREAM_UNAVAILABLE,
    /** 缺少必要配置（如 API key 未注入）。 */
    MISSING_CONFIG,
    /** 输出不是合法 JSON。 */
    INVALID_JSON,
    /** JSON 结构/枚举/必填字段违反契约。 */
    SCHEMA_VIOLATION,
    /** 模型自报置信度低于业务阈值。 */
    LOW_CONFIDENCE,
    /** 输出引用了输入候选之外的资源 ID。 */
    CANDIDATE_VIOLATION
}
