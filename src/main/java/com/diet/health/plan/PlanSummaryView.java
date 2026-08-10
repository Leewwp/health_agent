package com.diet.health.plan;

import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.PlanValidationLevel;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 计划列表摘要（规格 6.2 GET /plans）。 */
public record PlanSummaryView(Long id, PlanStatus status, LocalDate weekStart, String timezone,
                              PlanValidationLevel validationLevel, Long currentVersion, int itemCount,
                              LocalDateTime updatedAt) {
}
