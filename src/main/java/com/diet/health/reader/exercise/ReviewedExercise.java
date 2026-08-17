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
        String thumbnailUrl,
        String mediaUrl,
        String mediaState,
        String mediaCredit,
        String sourceName,
        String sourceId,
        String sourceVersion
) {

    /** 在读取模块边界完成深拷贝，避免调用方修改共享快照。 */
    public ReviewedExercise {
        aliases = immutableList(aliases);
        targetMuscles = immutableList(targetMuscles);
        secondaryMuscles = immutableList(secondaryMuscles);
        riskTags = immutableList(riskTags);
        steps = immutableList(steps);
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
