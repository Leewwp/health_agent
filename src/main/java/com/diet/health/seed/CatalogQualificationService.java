package com.diet.health.seed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 完整目录的确定性计划资格补全（ADR-0011）。
 * <p>
 * 只处理带有完整来源、部位、器材和中文步骤的公开目录数据；不调用模型、不生成训练剂量，
 * 生成结果仍须经过现有候选筛选和计划 Guard。该能力默认只在 dev 开启，生产可通过配置关闭。
 */
@Component
public class CatalogQualificationService {

    public static final String QUALIFICATION_VERSION = "auto-catalog-plan-v1";

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    public CatalogQualificationService(JdbcTemplate jdbcTemplate,
                                       @Value("${diet.seed.auto-qualify-catalog:false}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    /** 启动时幂等补全动作计划资格与可用公共餐食。 */
    public QualificationResult qualify() {
        if (!enabled) {
            return new QualificationResult(false, 0, 0);
        }
        int exercises = jdbcTemplate.update("""
                UPDATE exercise_item
                SET review_status = 'APPROVED',
                    plan_ready = 1,
                    difficulty = CASE
                        WHEN difficulty IS NULL OR TRIM(difficulty) = '' OR difficulty = '未评估'
                            THEN CASE
                                WHEN LOWER(COALESCE(name_en, name, '')) REGEXP 'stretch|mobility|warm[ -]?up|assisted|wall|rehabilitation'
                                    THEN '入门'
                                WHEN LOWER(COALESCE(name_en, name, '')) REGEXP 'jump|plyometric|burpee|handstand|muscle[ -]?up|one[ -]?arm'
                                    THEN '挑战'
                                ELSE '进阶'
                            END
                        ELSE difficulty
                    END,
                    movement_pattern = CASE
                        WHEN movement_pattern IS NULL OR TRIM(movement_pattern) = '' OR movement_pattern = '未评估'
                            THEN CASE WHEN body_part = 'cardio' OR category = 'cardio' THEN '有氧' ELSE '力量' END
                        ELSE movement_pattern
                    END
                WHERE source_name = 'gym-visual-exercises-dataset'
                  AND review_status = 'PENDING'
                  AND plan_ready = 0
                  AND COALESCE(name, '') <> ''
                  AND COALESCE(name_en, '') <> ''
                  AND COALESCE(body_part, '') <> ''
                  AND COALESCE(equipment, '') <> ''
                  AND COALESCE(instructions_zh, '') <> ''
                  AND JSON_TYPE(steps_json) = 'ARRAY'
                  AND JSON_LENGTH(steps_json) > 0
                """);

        int meals = jdbcTemplate.update("""
                UPDATE meal_item
                SET review_status = 'APPROVED', updated_at = NOW()
                WHERE source_type = 'PUBLIC'
                  AND owner_user_id IS NULL
                  AND review_status = 'PENDING'
                  AND COALESCE(name, '') <> ''
                  AND JSON_TYPE(meal_time) = 'ARRAY'
                  AND JSON_LENGTH(meal_time) > 0
                  AND JSON_TYPE(ingredients_json) = 'ARRAY'
                  AND JSON_LENGTH(ingredients_json) > 0
                  AND calories_kcal IS NOT NULL AND calories_kcal >= 0
                  AND protein_g IS NOT NULL AND protein_g >= 0
                  AND fat_g IS NOT NULL AND fat_g >= 0
                  AND carbohydrate_g IS NOT NULL AND carbohydrate_g >= 0
                """);

        // 目标标签是由动作模式确定性推导，不能依赖 seed 文件是否先于完整目录导入。
        jdbcTemplate.update("""
                UPDATE exercise_item
                SET training_goals = CASE
                    WHEN movement_pattern = '有氧' THEN JSON_ARRAY('减脂', '耐力', '保持健康')
                    ELSE JSON_ARRAY('增肌', '力量', '保持健康')
                END
                WHERE source_name = 'gym-visual-exercises-dataset'
                  AND review_status = 'APPROVED'
                  AND plan_ready = 1
                """);
        return new QualificationResult(true, exercises, meals);
    }

    public record QualificationResult(boolean enabled, int qualifiedExercises, int qualifiedMeals) {
    }
}
