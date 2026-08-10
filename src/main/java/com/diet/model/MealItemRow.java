package com.diet.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MealItemRow {
    private Long id;
    private String sourceType;
    private Long ownerUserId;
    private String name;
    private String nameEn;
    private String aliases;
    private String mealTime;
    private String mood;
    private String scene;
    private String healthGoal;
    private String cuisine;
    private String taste;
    private String convenience;
    private String description;
    private String ingredientsJson;
    private Integer servingCount;
    private BigDecimal servingSize;
    private String servingUnit;
    private BigDecimal caloriesKcal;
    private BigDecimal proteinG;
    private BigDecimal fatG;
    private BigDecimal carbohydrateG;
    private String nutritionBasis;
    private Boolean nutritionEstimated;
    private String allergenJson;
    private String allergenStatus;
    private String reviewStatus;
    private String sourceName;
    private String sourceId;
    private String sourceVersion;
    private String mediaUrl;
    private String mediaStatus;
    private String mediaCredit;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}