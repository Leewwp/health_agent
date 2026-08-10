package com.diet.health.plan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/** 计划项目视图（含确定性计划参数）。 */
public record PlanItemView(Long id, String resourceType, String resourceId, String name, LocalDate localDate,
                           LocalTime startTime, LocalTime endTime, String note, Map<String, Object> params) {
}
