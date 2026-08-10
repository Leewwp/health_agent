package com.diet.health.rag;

/**
 * 餐食检索器 seam（33 号票 RAG）。
 * 业务模块只依赖本接口；结构化与 hybrid 实现按配置切换，embedding 失败自动降级。
 */
public interface MealRetriever {

    /**
     * 按查询检索餐食候选，最多返回 limit 条（已重排）。
     * 硬约束（排除 ID、过敏原）在打分前过滤，结果模式与降级原因随结果返回。
     */
    RetrievalResult retrieve(MealRetrievalQuery query, int limit);
}
