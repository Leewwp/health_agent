package com.diet.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** weekly_plan 表行（34 号票计划聚合根）。 */
@Data
public class WeeklyPlanRow {
    private Long id;
    private Long userId;
    private String planScope;
    private String status;
    private LocalDate weekStart;
    private String timezone;
    private Long profileVersionNo;
    private Integer calorieLow;
    private Integer calorieHigh;
    private String rulesVersion;
    private String validationLevel;
    private String validationJson;
    private String note;
    private String sourceSessionId;
    private String generationSource;
    private String generationMetadataJson;
    private Long currentVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
