package com.diet.health.seed;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 完整目录自动资格补全的开关、数量和 SQL 边界测试。 */
class CatalogQualificationServiceTest {

    @Test
    void 关闭时不触碰数据库() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        CatalogQualificationService.QualificationResult result =
                new CatalogQualificationService(jdbc, false).qualify();

        assertFalse(result.enabled());
        assertEquals(0, result.qualifiedExercises());
        assertEquals(0, result.qualifiedMeals());
        verifyNoInteractions(jdbc);
    }

    @Test
    void 开启时补全动作和公共餐食并重算训练目标() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("UPDATE exercise_item"))).thenReturn(1294);
        when(jdbc.update(contains("UPDATE meal_item"))).thenReturn(4);
        when(jdbc.update(contains("SET training_goals"))).thenReturn(1324);

        CatalogQualificationService.QualificationResult result =
                new CatalogQualificationService(jdbc, true).qualify();

        assertEquals(1294, result.qualifiedExercises());
        assertEquals(4, result.qualifiedMeals());
        verify(jdbc, times(2)).update(contains("review_status = 'PENDING'"));
        verify(jdbc, times(2)).update(contains("UPDATE exercise_item"));
        verify(jdbc).update(contains("UPDATE meal_item"));
        verify(jdbc).update(contains("JSON_LENGTH(steps_json) > 0"));
        verify(jdbc).update(contains("owner_user_id IS NULL"));
        verify(jdbc).update(contains("SET training_goals"));
    }
}
