package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 计划写请求幂等结果快照。 */
@Data
public class PlanWriteRequestRow {
    private Long userId;
    private String requestId;
    private Long planId;
    private String operation;
    private String responseJson;
    private LocalDateTime createdAt;
}
