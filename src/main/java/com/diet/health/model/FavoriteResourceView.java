package com.diet.health.model;

import java.time.LocalDateTime;

/** 个人收藏条目。 */
public record FavoriteResourceView(String resourceType, String resourceId, LocalDateTime createdAt) {
}
