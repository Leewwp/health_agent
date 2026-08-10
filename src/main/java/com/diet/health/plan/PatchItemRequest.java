package com.diet.health.plan;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 计划项目编辑请求（规格 6.3）：
 * PATCH 只允许修改已有项目的日期、时间和备注，不允许修改资源营养、训练剂量和作息规则。
 */
public record PatchItemRequest(LocalDate localDate, LocalTime startTime, LocalTime endTime, String note) {
}
