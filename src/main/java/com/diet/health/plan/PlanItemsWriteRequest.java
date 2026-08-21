package com.diet.health.plan;

import java.util.List;

/** 计划项目的一次性替换保存边界。 */
public record PlanItemsWriteRequest(String requestId, Long expectedVersion, String name, List<PlanItemWrite> items) {
    public PlanItemsWriteRequest(String requestId, Long expectedVersion, List<PlanItemWrite> items) {
        this(requestId, expectedVersion, null, items);
    }

    public PlanItemsWriteRequest {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
