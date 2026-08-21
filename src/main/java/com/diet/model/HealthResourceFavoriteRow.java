package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

/** health_resource_favorite 表行。 */
@Data
public class HealthResourceFavoriteRow {
    private Long userId;
    private String resourceType;
    private String resourceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
