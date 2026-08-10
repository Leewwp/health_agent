package com.diet.health.browse;

import com.diet.exception.DietException;
import com.diet.health.model.MealBrowseItem;
import com.diet.health.model.PagedResponse;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 餐食浏览服务测试（33 号票）。
 * 接缝：MealBrowseService + mock MealMapper。验证分页参数校验、偏移计算、
 * 空数据、字段映射（营养估算标记、过敏原、槽位标签、媒体与来源状态）。
 */
class MealBrowseServiceTest {

    private MealMapper mealMapper;
    private MealBrowseService service;

    @BeforeEach
    void setUp() {
        mealMapper = mock(MealMapper.class);
        service = new MealBrowseService(mealMapper, new JsonService(new ObjectMapper()));
    }

    @Test
    void 分页参数校验_page小于1抛参数错误() {
        assertThrows(DietException.class, () -> service.browse(0, 20), "page 必须不小于 1");
        assertThrows(DietException.class, () -> service.browse(-1, 20));
    }

    @Test
    void 分页参数校验_size超过50或小于1抛参数错误() {
        assertThrows(DietException.class, () -> service.browse(1, 51), "size 上限 50");
        assertThrows(DietException.class, () -> service.browse(1, 0));
        assertThrows(DietException.class, () -> service.browse(1, -5));
    }

    @Test
    void 边界值50与1都合法() {
        when(mealMapper.browsePublicMeals(0, 50)).thenReturn(List.of());
        when(mealMapper.countPublicMeals()).thenReturn(0);
        PagedResponse<MealBrowseItem> response = service.browse(1, 50);
        assertEquals(50, response.size());
        assertEquals(1, response.page());
    }

    @Test
    void 分页偏移按page计算() {
        service.browse(2, 20);
        verify(mealMapper).browsePublicMeals(20, 20);
        service.browse(3, 15);
        verify(mealMapper).browsePublicMeals(30, 15);
    }

    @Test
    void 空数据返回空列表和total0() {
        when(mealMapper.browsePublicMeals(0, 20)).thenReturn(List.of());
        when(mealMapper.countPublicMeals()).thenReturn(0);
        PagedResponse<MealBrowseItem> response = service.browse(1, 20);
        assertTrue(response.items().isEmpty());
        assertEquals(0, response.total());
        assertEquals(0, response.totalPages());
    }

    @Test
    void 分页总数与总页数正确() {
        when(mealMapper.browsePublicMeals(0, 20)).thenReturn(List.of(row()));
        when(mealMapper.countPublicMeals()).thenReturn(45);
        PagedResponse<MealBrowseItem> response = service.browse(1, 20);
        assertEquals(45, response.total());
        assertEquals(3, response.totalPages());
    }

    @Test
    void 字段映射完整_营养估算与过敏原状态透出() {
        when(mealMapper.browsePublicMeals(0, 20)).thenReturn(List.of(row()));
        when(mealMapper.countPublicMeals()).thenReturn(1);
        MealBrowseItem item = service.browse(1, 20).items().get(0);

        assertEquals(100L, item.id());
        assertEquals("番茄鸡蛋面", item.name());
        assertEquals("Tomato Egg Noodles", item.nameEn());
        assertEquals(List.of("番茄面"), item.aliases());
        assertEquals(new BigDecimal("245.00"), item.nutrition().caloriesKcal());
        assertEquals(new BigDecimal("12.00"), item.nutrition().proteinG());
        assertTrue(item.nutrition().estimated());
        assertEquals("foodcom_source_value", item.nutrition().basis());
        assertEquals(4, item.serving().count());
        assertEquals("份", item.serving().unit());
        assertEquals(List.of("鸡蛋"), item.allergens());
        assertEquals("REVIEWED", item.allergenStatus());
        assertEquals("APPROVED", item.reviewStatus());
        assertEquals("NONE", item.mediaStatus());
        assertEquals("foodcom-recipes-and-reviews-v2", item.sourceName());
        assertEquals("317010", item.sourceId());
        assertEquals("v2", item.sourceVersion());
    }

    @Test
    void 槽位标签映射为七维map() {
        when(mealMapper.browsePublicMeals(0, 20)).thenReturn(List.of(row()));
        when(mealMapper.countPublicMeals()).thenReturn(1);
        Map<String, List<String>> tags = service.browse(1, 20).items().get(0).tags();

        assertEquals(List.of("早餐", "午餐"), tags.get("mealTime"));
        assertEquals(List.of("高蛋白"), tags.get("healthGoal"));
        assertEquals(List.of("中式"), tags.get("cuisine"));
        assertEquals(List.of("鲜"), tags.get("taste"));
        assertTrue(tags.containsKey("mood"));
        assertTrue(tags.containsKey("scene"));
        assertTrue(tags.containsKey("convenience"));
    }

    @Test
    void 空JSON字段映射为空集合() {
        when(mealMapper.browsePublicMeals(0, 20)).thenReturn(List.of(row()));
        when(mealMapper.countPublicMeals()).thenReturn(1);
        MealBrowseItem item = service.browse(1, 20).items().get(0);
        assertTrue(item.tags().get("mood").isEmpty());
        assertTrue(item.tags().get("scene").isEmpty());
        assertTrue(item.tags().get("convenience").isEmpty());
        assertEquals(List.of("番茄", "鸡蛋", "面条"), item.ingredients());
    }

    private MealItemRow row() {
        MealItemRow row = new MealItemRow();
        row.setId(100L);
        row.setSourceType("PUBLIC");
        row.setName("番茄鸡蛋面");
        row.setNameEn("Tomato Egg Noodles");
        row.setAliases("[\"番茄面\"]");
        row.setMealTime("[\"早餐\",\"午餐\"]");
        row.setMood("[]");
        row.setScene("[]");
        row.setHealthGoal("[\"高蛋白\"]");
        row.setCuisine("[\"中式\"]");
        row.setTaste("[\"鲜\"]");
        row.setConvenience("[]");
        row.setDescription("清爽家常面");
        row.setIngredientsJson("[\"番茄\",\"鸡蛋\",\"面条\"]");
        row.setServingCount(4);
        row.setServingSize(new BigDecimal("1.00"));
        row.setServingUnit("份");
        row.setCaloriesKcal(new BigDecimal("245.00"));
        row.setProteinG(new BigDecimal("12.00"));
        row.setFatG(new BigDecimal("8.00"));
        row.setCarbohydrateG(new BigDecimal("30.00"));
        row.setNutritionBasis("foodcom_source_value");
        row.setNutritionEstimated(true);
        row.setAllergenJson("[\"鸡蛋\"]");
        row.setAllergenStatus("REVIEWED");
        row.setReviewStatus("APPROVED");
        row.setSourceName("foodcom-recipes-and-reviews-v2");
        row.setSourceId("317010");
        row.setSourceVersion("v2");
        row.setMediaUrl(null);
        row.setMediaStatus("NONE");
        row.setMediaCredit(null);
        return row;
    }
}
