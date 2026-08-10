package com.diet.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** weekly_plan_item 表行（34 号票计划项目）。 */
@Data
public class WeeklyPlanItemRow {
    private Long id;
    private Long planId;
    private Long versionNo;
    private String resourceType;
    private String resourceId;
    private String name;
    private LocalDate localDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String note;
    private String planParamsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
