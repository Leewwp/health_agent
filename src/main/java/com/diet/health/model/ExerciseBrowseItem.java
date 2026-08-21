package com.diet.health.model;

import java.util.List;

/** 动作浏览条目（规格 6.2，GET /api/v1/health/exercises）。 */
public record ExerciseBrowseItem(
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
        String sourceVersion,
        String sourceHash,
        String sourceCategory,
        String sourceBodyPart,
        String sourceEquipment,
        String sourceTarget,
        String sourceMuscleGroup,
        List<String> sourceSecondaryMuscles,
        String instructionsZhStatus,
        String qualificationVersion,
        boolean qualificationVisible,
        boolean qualificationRecommendable,
        boolean qualificationPlanReady,
        String qualificationReportJson,
        boolean favorite
) {
}
