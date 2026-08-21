package com.diet.health.plan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/** 批量编辑中的单个计划项目；id 为空表示新增。 */
public record PlanItemWrite(Long id, String resourceType, String resourceId, String name,
                            LocalDate localDate, LocalTime startTime, LocalTime endTime,
                            String note, Map<String, Object> planParams) {
    public PlanItemWrite {
        planParams = planParams == null ? Map.of() : Map.copyOf(planParams);
    }

    public PlanItemDraft toDraft() {
        return new PlanItemDraft(resourceType, resourceId, name, localDate, startTime, endTime, note, planParams);
    }
}
