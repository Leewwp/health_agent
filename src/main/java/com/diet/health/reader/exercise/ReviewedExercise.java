package com.diet.health.reader.exercise;

import java.util.List;

/**
 * 审核动作浏览读取模型（#64，方案 B）。
 * <p>
 * 面向用户的槽位字段（category/bodyPart/targetMuscles/secondaryMuscles/equipment/difficulty）
 * 已按 {@link ExerciseVocabulary} 归一为健身槽位中文词汇；未收录的原始值过滤为空
 * （不原样透出英文）。nameEn/sourceId 等原始资料字段保留，不与用户筛选标签混用。
 */
public record ReviewedExercise(
        Long id,
        String name,
        String nameEn,
        List<String> aliases,
        String category,
        String bodyPart,
        List<String> targetMuscles,
        List<String> secondaryMuscles,
        String equipment,
        String difficulty,
        String movementPattern,
        List<String> riskTags,
        String alternativeGroup,
        String reviewStatus,
        boolean planReady,
        String instructionsZh,
        List<String> steps,
        String mediaState,
        String mediaCredit,
        String sourceName,
        String sourceId,
        String sourceVersion
) {
}
