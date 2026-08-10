package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

/** exercise_item 表行（33 号票审核动作资源）。 */
@Data
public class ExerciseItemRow {
    private Long id;
    private String sourceName;
    private String sourceId;
    private String sourceVersion;
    private String name;
    private String nameEn;
    private String aliases;
    private String category;
    private String bodyPart;
    private String targetMuscles;
    private String secondaryMuscles;
    private String equipment;
    private String difficulty;
    private String movementPattern;
    private String riskTags;
    private String alternativeGroup;
    private String reviewStatus;
    private Boolean planReady;
    private String instructionsZh;
    private String stepsJson;
    private String mediaState;
    private String mediaCredit;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
