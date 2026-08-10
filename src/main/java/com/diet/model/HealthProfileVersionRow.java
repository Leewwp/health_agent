package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

/** health_profile_version 表行（34 号票档案版本快照）。 */
@Data
public class HealthProfileVersionRow {
    private Long id;
    private Long userId;
    private Long profileId;
    private Long versionNo;
    private String snapshotJson;
    private Integer calorieLow;
    private Integer calorieHigh;
    private LocalDateTime createdAt;
}
