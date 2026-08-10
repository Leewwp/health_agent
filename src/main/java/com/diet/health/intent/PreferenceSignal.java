package com.diet.health.intent;

/** 用户对某类资源的明示偏好信号。 */
public record PreferenceSignal(String resourceType, String resourceId, String action) {
}
