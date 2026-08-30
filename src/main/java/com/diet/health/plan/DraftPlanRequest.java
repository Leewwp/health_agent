package com.diet.health.plan;

import com.diet.health.enums.PlanScope;
import java.time.LocalDate;

/**
 * 创建周计划草稿请求（规格 6.3）。
 * weekStart 为生成边界的内部周锚点（ADR-0018）：由生成服务按“生成当天所在周的周一”派生，
 * 不是用户必填项；timezone 缺省 Asia/Shanghai；trainingFocus 为可选主训练部位偏好。
 */
public record DraftPlanRequest(String sessionId, LocalDate weekStart, String timezone, String trainingFocus,
                               PlanScope planScope) {
    /** 兼容源码调用，但不再提供默认混合范围；缺少范围的写请求会被 Guard 拒绝。 */
    public DraftPlanRequest(String sessionId, LocalDate weekStart, String timezone, String trainingFocus) {
        this(sessionId, weekStart, timezone, trainingFocus, null);
    }
}
