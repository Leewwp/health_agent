package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * weekly_plan_version 表行（34 号票计划版本快照，42 号票补生成依据）。
 * 生成依据不可变：档案快照、规则版本、来源会话、作息事实来源、资源快照。
 * 时区不是版本字段：版本从计划根 weekly_plan.timezone 继承（计划创建时固定，无时区编辑入口）。
 */
@Data
public class WeeklyPlanVersionRow {
    private Long id;
    private Long planId;
    private Long versionNo;
    private Long profileVersionNo;
    private String profileSnapshotJson;
    private String rulesVersion;
    private String sourceSessionId;
    private String factSourcesJson;
    private String resourceSnapshotJson;
    private String validationJson;
    private LocalDateTime createdAt;
}
