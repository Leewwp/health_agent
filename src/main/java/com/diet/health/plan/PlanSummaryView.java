package com.diet.health.plan;

import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.enums.PlanScope;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 计划列表摘要（规格 6.2 GET /plans）。 */
public record PlanSummaryView(Long id, PlanStatus status, LocalDate weekStart, String timezone,
                              PlanValidationLevel validationLevel, Long currentVersion, int itemCount,
                              String generationSource, LocalDateTime updatedAt, PlanScope planScope) {
    public PlanSummaryView(Long id, PlanStatus status, LocalDate weekStart, String timezone,
                           PlanValidationLevel validationLevel, Long currentVersion, int itemCount,
                           String generationSource, LocalDateTime updatedAt) {
        this(id, status, weekStart, timezone, validationLevel, currentVersion, itemCount,
                generationSource, updatedAt, PlanScope.EXERCISE);
    }
}
