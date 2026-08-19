package com.diet.health.model;

/** 健康聊天响应中的明确用户操作。 */
public record HealthAction(String type, String label, String requestId) {
}
