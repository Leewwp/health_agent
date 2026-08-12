package com.diet.health.reader.meal;

import java.util.List;
import java.util.Map;

/**
 * 审核公共餐食读取模块接口（#68，方案 B）。
 * <p>
 * 只有 {@link DbReviewedMealReader} 可直接依赖 MealMapper；调用方（浏览、RAG、索引、评估）
 * 只消费本接口与 {@link ReviewedMeal} 视图。所有查询只暴露 APPROVED + PUBLIC 餐食。
 */
public interface ReviewedMealReader {

    /**
     * 结构化条件召回：7 维槽位 JSON_OVERLAPS 召回后过滤 APPROVED + PUBLIC，排序与 SQL 口径一致。
     * 返回的集合由实现决定顺序（旧链路 updated_at DESC），调用方只做领域级过滤与重排。
     *
     * @param slots 7 维健康槽位（可为空，空维度视为不约束）
     * @param limit SQL 召回上限
     */
    List<ReviewedMeal> recallStructured(Map<String, List<String>> slots, int limit);

    /**
     * 按一组稳定 ID 批量回查：丢弃不存在、已降级审核状态或非公开记录，
     * 去重并按 id 升序返回；空 ID 集合返回空集合，不触发 SQL。
     */
    List<ReviewedMeal> findByIds(List<Long> ids);

    /** 审核公共餐食分页（offset 从 0 起，id 升序稳定排序）。 */
    List<ReviewedMeal> browse(int offset, int size);

    /** 审核公共餐食总数（与 browse 同口径）。 */
    int countPublic();

    /** Embedding/向量索引/评估快照所需的稳定列表（APPROVED + PUBLIC，id 升序）。 */
    List<ReviewedMeal> snapshotAll();
}
