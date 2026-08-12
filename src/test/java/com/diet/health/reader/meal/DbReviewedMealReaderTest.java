package com.diet.health.reader.meal;

import com.diet.enums.SourceMode;
import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核餐食读取模块契约测试（#68，方案 B）。
 * <p>
 * mock Mapper 验证接口方法选择正确 SQL 与行级过滤：APPROVED + PUBLIC 唯一入口、
 * 批量回查丢弃无效/非公开/重复 ID 且空 ID 不触发无界 SQL、空库返回不可变空集合、
 * 行 → 视图映射与过敏原解析口径一致。
 */
class DbReviewedMealReaderTest {

    private MealMapper mealMapper;
    private DbReviewedMealReader reader;

    @BeforeEach
    void setUp() {
        mealMapper = mock(MealMapper.class);
        reader = new DbReviewedMealReader(mealMapper, new JsonService(new ObjectMapper()));
    }

    @Test
    void 结构化召回只暴露APPROVED行且选择searchSQL() {
        MealItemRow approved = row(1L, "APPROVED");
        MealItemRow pending = row(2L, "PENDING");
        MealItemRow rejected = row(3L, "REJECTED");
        when(mealMapper.search(eq(SourceMode.PUBLIC), eq(null), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), eq(50)))
                .thenReturn(List.of(approved, pending, rejected));

        List<ReviewedMeal> result = reader.recallStructured(
                Map.of("mealTime", List.of("早餐")), 50);

        assertEquals(List.of(1L), result.stream().map(ReviewedMeal::id).toList(),
                "PENDING/REJECTED 行不得进入审核检索候选");
    }

    @Test
    void 结构化召回空槽位转换为空JSON数组() {
        when(mealMapper.search(eq(SourceMode.PUBLIC), eq(null), eq("[]"), eq("[]"), eq("[]"),
                eq("[]"), eq("[]"), eq("[]"), eq("[]"), eq(50)))
                .thenReturn(List.of());
        assertTrue(reader.recallStructured(null, 50).isEmpty());
    }

    @Test
    void 批量回查丢弃无效非公开和重复ID且保持冻结排序() {
        // adapter 先做调用侧去重；mapper 按冻结排序返回（id 升序）；未知/非公开 ID 由 SQL 丢弃
        when(mealMapper.findApprovedPublicByIds(List.of(1L, 2L, 3L, 99L)))
                .thenReturn(List.of(row(1L, "APPROVED"), row(3L, "APPROVED")));

        List<ReviewedMeal> result = reader.findByIds(List.of(1L, 1L, 2L, 3L, 99L));

        verify(mealMapper).findApprovedPublicByIds(List.of(1L, 2L, 3L, 99L));
        assertEquals(List.of(1L, 3L), result.stream().map(ReviewedMeal::id).toList(),
                "只返回有效唯一结果并保持冻结排序");
    }

    @Test
    void 空ID集合不触发SQL且返回空() {
        List<ReviewedMeal> result = reader.findByIds(List.of());
        assertTrue(result.isEmpty());
        assertTrue(reader.findByIds(null).isEmpty());
        verify(mealMapper, never()).findApprovedPublicByIds(anyList());
    }

    @Test
    void 分页与总数选择对应SQL() {
        when(mealMapper.browsePublicMeals(20, 10)).thenReturn(List.of(row(5L, "APPROVED")));
        when(mealMapper.countPublicMeals()).thenReturn(295);
        assertEquals(1, reader.browse(20, 10).size());
        assertEquals(295, reader.countPublic());
        verify(mealMapper).browsePublicMeals(20, 10);
        verify(mealMapper).countPublicMeals();
    }

    @Test
    void 快照选择稳定列表SQL() {
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(row(1L, "APPROVED")));
        List<ReviewedMeal> snapshot = reader.snapshotAll();
        assertEquals(1, snapshot.size());
        assertEquals(1L, snapshot.get(0).id());
        verify(mealMapper).findApprovedPublicMeals();
    }

    @Test
    void 行映射为完整视图且JSON空值空数组多值一致() {
        MealItemRow row = row(7L, "APPROVED");
        ReviewedMeal meal = reader.toReviewedMeal(row);

        assertEquals(7L, meal.id());
        assertEquals("番茄鸡蛋面", meal.name());
        assertEquals("Tomato Egg Noodles", meal.nameEn());
        assertEquals(List.of("番茄面"), meal.aliases());
        assertEquals(List.of("早餐", "午餐"), meal.tags().get("mealTime"));
        assertTrue(meal.tags().get("mood").isEmpty());
        assertEquals(List.of("高蛋白"), meal.tags().get("healthGoal"));
        assertEquals(List.of("鸡蛋", "麸质"), meal.allergens());
        assertEquals("REVIEWED", meal.allergenStatus());
        assertEquals("APPROVED", meal.reviewStatus());
        assertEquals("NONE", meal.mediaStatus());
        assertEquals("foodcom-recipes-and-reviews-v2", meal.sourceName());
        assertEquals("317010", meal.sourceId());
        assertEquals("v2", meal.sourceVersion());
        assertEquals("PUBLIC", meal.sourceType());
        assertEquals(new BigDecimal("245.00"), meal.nutrition().caloriesKcal());
        assertTrue(meal.nutrition().estimated());
        assertEquals(4, meal.serving().count());
    }

    @Test
    void 返回集合不可变() {
        when(mealMapper.browsePublicMeals(0, 10)).thenReturn(List.of(row(1L, "APPROVED")));
        List<ReviewedMeal> meals = reader.browse(0, 10);
        assertThrows(UnsupportedOperationException.class,
                () -> meals.add(reader.toReviewedMeal(row(2L, "APPROVED"))),
                "读取模块返回的集合必须不可变");
    }

    private MealItemRow row(Long id, String reviewStatus) {
        MealItemRow row = new MealItemRow();
        row.setId(id);
        row.setSourceType("PUBLIC");
        row.setName("番茄鸡蛋面");
        row.setNameEn("Tomato Egg Noodles");
        row.setAliases("[\"番茄面\"]");
        row.setMealTime("[\"早餐\",\"午餐\"]");
        row.setMood("[]");
        row.setScene("[]");
        row.setHealthGoal("[\"高蛋白\"]");
        row.setCuisine("[]");
        row.setTaste("[]");
        row.setConvenience("[]");
        row.setDescription("清爽家常面");
        row.setIngredientsJson("[\"番茄\",\"鸡蛋\"]");
        row.setServingCount(4);
        row.setServingSize(new BigDecimal("1.00"));
        row.setServingUnit("份");
        row.setCaloriesKcal(new BigDecimal("245.00"));
        row.setProteinG(new BigDecimal("12.00"));
        row.setFatG(new BigDecimal("8.00"));
        row.setCarbohydrateG(new BigDecimal("30.00"));
        row.setNutritionBasis("foodcom_source_value");
        row.setNutritionEstimated(true);
        row.setAllergenJson("[\"鸡蛋\",\"麸质\"]");
        row.setAllergenStatus("REVIEWED");
        row.setReviewStatus(reviewStatus);
        row.setSourceName("foodcom-recipes-and-reviews-v2");
        row.setSourceId("317010");
        row.setSourceVersion("v2");
        row.setMediaStatus("NONE");
        return row;
    }
}
