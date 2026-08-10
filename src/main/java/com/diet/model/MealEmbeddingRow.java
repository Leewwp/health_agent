package com.diet.model;

import lombok.Data;

import java.time.LocalDateTime;

/** meal_item_embedding 表行（33 号票餐食向量）。 */
@Data
public class MealEmbeddingRow {
    private Long id;
    private Long mealId;
    private String model;
    private String modelVersion;
    private Integer dimension;
    private String vector;
    private LocalDateTime createdAt;
}
