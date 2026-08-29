package com.diet.health.module;

import java.util.List;

/**
 * 面向周计划组合的餐食候选（57 号票）：类型化资源身份 + 名称 + 餐次标签 + 每份热量。
 * <p>
 * 两种 Provider 模式各自提供完整互斥的候选集合：REVIEWED_DB 为审核 APPROVED 公共餐食
 * （resourceId 为数据库主键），FIXTURE_SEED 为内存种子餐食（M1-M9），组合器/挑选器
 * 只消费本类型，不接触 Mapper 行对象。
 * caloriesKcal 可能为 null（无每份热量口径），由挑选器按既有降级策略过滤；
 * mealTimeTags 为空表示未打餐次标签（视为全时段可用）。sortKey 为候选在集合内的
 * 确定性排序键（正式模式为数据库主键序，fixture 模式为种子列表序），保证挑选结果可复现。
 * <p>
 * 简报补充回路：扩展受控偏好标签 cuisineTags / foodTypeTags / tasteTags /
 * nutritionPreferenceTags / convenienceTags（均为列表，永不为 null）。审核库 Provider 从
 * meal_item.cuisine/taste/health_goal/convenience 填充，nutritionPreferenceTags 取
 * health_goal 中除“减脂、增肌、维持健康、均衡”以外的规范值；fixture 种子按规格的
 * 确定性标签表填充，保证两种模式都有可验证基准。挑选器只消费本类型。
 */
public record PlanMealCandidate(
        String resourceType,
        String resourceId,
        String name,
        List<String> mealTimeTags,
        Integer caloriesKcal,
        long sortKey,
        List<String> cuisineTags,
        List<String> foodTypeTags,
        List<String> tasteTags,
        List<String> nutritionPreferenceTags,
        List<String> convenienceTags
) {
    public PlanMealCandidate {
        resourceType = resourceType == null ? "MEAL" : resourceType;
        mealTimeTags = mealTimeTags == null ? List.of() : List.copyOf(mealTimeTags);
        cuisineTags = cuisineTags == null ? List.of() : List.copyOf(cuisineTags);
        foodTypeTags = foodTypeTags == null ? List.of() : List.copyOf(foodTypeTags);
        tasteTags = tasteTags == null ? List.of() : List.copyOf(tasteTags);
        nutritionPreferenceTags = nutritionPreferenceTags == null ? List.of() : List.copyOf(nutritionPreferenceTags);
        convenienceTags = convenienceTags == null ? List.of() : List.copyOf(convenienceTags);
    }

    /** 兼容旧调用：无偏好标签的候选（标签为空列表）。 */
    public PlanMealCandidate(String resourceType, String resourceId, String name, List<String> mealTimeTags,
                             Integer caloriesKcal, long sortKey) {
        this(resourceType, resourceId, name, mealTimeTags, caloriesKcal, sortKey,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 兼容已有四类偏好标签构造。 */
    public PlanMealCandidate(String resourceType, String resourceId, String name, List<String> mealTimeTags,
                             Integer caloriesKcal, long sortKey, List<String> cuisineTags,
                             List<String> tasteTags, List<String> nutritionPreferenceTags,
                             List<String> convenienceTags) {
        this(resourceType, resourceId, name, mealTimeTags, caloriesKcal, sortKey,
                cuisineTags, List.of(), tasteTags, nutritionPreferenceTags, convenienceTags);
    }
}
