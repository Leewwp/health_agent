package com.diet.health.plan;

import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.enums.PlanScope;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 周计划视图（规格 6.3 计划接口）。explanation 为 Agent 对已校验结果的解释。
 * profileStale 表示当前健康档案版本已新于计划生成依据（规格 8.2，不静默重算，只标记较旧）。 */
public record PlanView(
        Long id,
        PlanStatus status,
        LocalDate weekStart,
        String timezone,
        Long profileVersionNo,
        Integer calorieLow,
        Integer calorieHigh,
        String rulesVersion,
        PlanValidationLevel validationLevel,
        List<RuleHitView> validationHits,
        String note,
        Long currentVersion,
        List<PlanItemView> items,
        boolean profileStale,
        String explanation,
        String generationSource,
        LocalDateTime updatedAt,
        PlanScope planScope
) {
    public PlanView(Long id, PlanStatus status, LocalDate weekStart, String timezone, Long profileVersionNo,
                    Integer calorieLow, Integer calorieHigh, String rulesVersion, PlanValidationLevel validationLevel,
                    List<RuleHitView> validationHits, String note, Long currentVersion, List<PlanItemView> items,
                    boolean profileStale, String explanation, String generationSource, LocalDateTime updatedAt) {
        this(id, status, weekStart, timezone, profileVersionNo, calorieLow, calorieHigh, rulesVersion,
                validationLevel, validationHits, note, currentVersion, items, profileStale, explanation,
                generationSource, updatedAt, PlanScope.EXERCISE);
    }
}
