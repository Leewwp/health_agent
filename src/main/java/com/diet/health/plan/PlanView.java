package com.diet.health.plan;

import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.enums.PlanScope;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 周计划视图（规格 6.3 计划接口）。explanation 为 Agent 对已校验结果的解释。
 * profileStale 表示当前健康档案版本已新于计划生成依据（规格 8.2，不静默重算，只标记较旧）。
 * generationNotes 为生成说明（未支持偏好 + 按日回退），从生成 metadata 透传；旧计划缺 metadata 时为非 null 空对象。 */
public record PlanView(
        Long id,
        String name,
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
        PlanScope planScope,
        GenerationNotes generationNotes
) {

    public PlanView {
        // 反序列化旧计划（无 generationNotes）与内部构造缺省时保持非 null 空对象
        generationNotes = generationNotes == null ? GenerationNotes.empty() : generationNotes;
    }

    public PlanView(Long id, PlanStatus status, LocalDate weekStart, String timezone, Long profileVersionNo,
                    Integer calorieLow, Integer calorieHigh, String rulesVersion, PlanValidationLevel validationLevel,
                    List<RuleHitView> validationHits, String note, Long currentVersion, List<PlanItemView> items,
                    boolean profileStale, String explanation, String generationSource, LocalDateTime updatedAt) {
        this(id, null, status, weekStart, timezone, profileVersionNo, calorieLow, calorieHigh, rulesVersion,
                validationLevel, validationHits, note, currentVersion, items, profileStale, explanation,
                generationSource, updatedAt, PlanScope.EXERCISE, GenerationNotes.empty());
    }

    /** 兼容旧的测试/调用方构造器，统一计划名称由服务端补齐。 */
    public PlanView(Long id, PlanStatus status, LocalDate weekStart, String timezone, Long profileVersionNo,
                    Integer calorieLow, Integer calorieHigh, String rulesVersion, PlanValidationLevel validationLevel,
                    List<RuleHitView> validationHits, String note, Long currentVersion, List<PlanItemView> items,
                    boolean profileStale, String explanation, String generationSource, LocalDateTime updatedAt,
                    PlanScope planScope) {
        this(id, null, status, weekStart, timezone, profileVersionNo, calorieLow, calorieHigh, rulesVersion,
                validationLevel, validationHits, note, currentVersion, items, profileStale, explanation,
                generationSource, updatedAt, planScope, GenerationNotes.empty());
    }
}
