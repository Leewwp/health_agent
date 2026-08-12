package com.diet.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** health_profile 表行（34 号票当前档案；62 号票补结构化风险字段）。 */
@Data
public class HealthProfileRow {
    private Long id;
    private Long userId;
    private Integer age;
    private String sex;
    private BigDecimal heightCm;
    private BigDecimal weightKg;
    private String activityLevel;
    private String goal;
    private String timezone;
    private Integer calorieLow;
    private Integer calorieHigh;
    private Boolean estimated;
    private String riskConditionsJson;
    private String riskNote;
    private Long versionNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
