package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

/** weekly_plan_version 表行（34 号票计划版本快照）。 */
@Data
public class WeeklyPlanVersionRow {
    private Long id;
    private Long planId;
    private Long versionNo;
    private Long profileVersionNo;
    private String profileSnapshotJson;
    private String validationJson;
    private LocalDateTime createdAt;
}
