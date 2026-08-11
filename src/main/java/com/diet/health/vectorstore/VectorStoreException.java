package com.diet.health.vectorstore;

/**
 * 向量存储基础设施故障（Qdrant 超时/不可用/维度不匹配等）。
 * 调用方捕获后降级为结构化检索；不属于领域业务错误。
 */
public class VectorStoreException extends RuntimeException {

    public VectorStoreException(String message) {
        super(message);
    }

    public VectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
