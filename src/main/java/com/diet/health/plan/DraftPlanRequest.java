package com.diet.health.plan;

import java.time.LocalDate;

/**
 * 创建周计划草稿请求（规格 6.3）。
 * weekStart 缺省为本地下周一；timezone 缺省 Asia/Shanghai；trainingFocus 为可选主训练部位偏好。
 */
public record DraftPlanRequest(String sessionId, LocalDate weekStart, String timezone, String trainingFocus) {
}
