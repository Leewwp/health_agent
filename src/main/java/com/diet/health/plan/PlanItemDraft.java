package com.diet.health.plan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * 计划项目草稿（组合器输出 / 校验器输入）。
 * resourceType+resourceId 为类型化资源身份；planParams 携带热量、部位、剂量等确定性计划参数。
 */
public record PlanItemDraft(
        String resourceType,
        String resourceId,
        String name,
        LocalDate localDate,
        LocalTime startTime,
        LocalTime endTime,
        String note,
        Map<String, Object> planParams
) {
    public PlanItemDraft {
        planParams = planParams == null ? Map.of() : Map.copyOf(planParams);
    }

    /** 计划参数中的热量（kcal），无则返回 null。 */
    public Integer caloriesKcal() {
        Object value = planParams.get("caloriesKcal");
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    /** 计划参数中的主训练部位，无则返回 null。 */
    public String bodyPart() {
        Object value = planParams.get("bodyPart");
        return value == null ? null : String.valueOf(value);
    }

    /** 是否是训练项目。 */
    public boolean isExercise() {
        return "EXERCISE".equals(resourceType);
    }

    /** 是否是作息项目。 */
    public boolean isRoutine() {
        return "ROUTINE".equals(resourceType);
    }

    /** 是否是餐食项目。 */
    public boolean isMeal() {
        return "MEAL".equals(resourceType);
    }
}
