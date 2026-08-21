package com.diet.health.browse;

import com.diet.exception.DietException;
import com.diet.exception.HealthApiException;
import com.diet.health.model.MealBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.health.reader.meal.ReviewedMeal;
import com.diet.health.reader.meal.ReviewedMealReader;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.ResourceMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 餐食浏览服务测试（33 号票；#69 迁移到审核读取模块 seam）。
 * 接缝：MealBrowseService + mock ReviewedMealReader（方案 B，浏览用例层不接触 Mapper 行对象）。
 * 验证分页参数校验、偏移计算、空数据与读取模型字段透传。
 */
class MealBrowseServiceTest {

    private ReviewedMealReader reviewedMealReader;
    private HealthResourceProvider resourceProvider;
    private MealBrowseService service;

    @BeforeEach
    void setUp() {
        reviewedMealReader = mock(ReviewedMealReader.class);
        resourceProvider = mock(HealthResourceProvider.class);
        when(resourceProvider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        service = new MealBrowseService(reviewedMealReader, resourceProvider);
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
        when(reviewedMealReader.browse(0, 50)).thenReturn(List.of());
        when(reviewedMealReader.countPublic()).thenReturn(0);
        PagedResponse<MealBrowseItem> response = service.browse(1, 50);
        assertEquals(50, response.size());
        assertEquals(1, response.page());
    }

    @Test
    void 分页偏移按page计算() {
        service.browse(2, 20);
        verify(reviewedMealReader).browse(20, 20);
        service.browse(3, 15);
        verify(reviewedMealReader).browse(30, 15);
    }

    @Test
    void 极大page返回400而非负偏移或500() {
        assertThrows(DietException.class, () -> service.browse(Integer.MAX_VALUE, 20), "page 溢出 int 范围应拒绝");
        assertThrows(DietException.class, () -> service.browse(Integer.MAX_VALUE, 50));
        assertThrows(DietException.class, () -> service.browse(100_000_000, 50), "offset 超出数据库安全范围应拒绝");
    }

    @Test
    void 查询和结构化筛选交给Reader并保持服务端分页() {
        Map<String, String> filters = Map.of("mealTime", "午餐", "healthGoal", "高蛋白");
        when(reviewedMealReader.browse(20, 20, 7L, true, "鸡", filters))
                .thenReturn(List.of(meal()));
        when(reviewedMealReader.countPublic(7L, true, "鸡", filters)).thenReturn(1);

        PagedResponse<MealBrowseItem> response = service.browse(2, 20, 7L, true, "鸡", filters);

        assertEquals(1, response.total());
        verify(reviewedMealReader).browse(20, 20, 7L, true, "鸡", filters);
        verify(reviewedMealReader).countPublic(7L, true, "鸡", filters);
    }

    @Test
    void 安全范围内极大page仍正常计算偏移() {
        when(reviewedMealReader.browse(199_999_980, 20)).thenReturn(List.of());
        when(reviewedMealReader.countPublic()).thenReturn(0);
        service.browse(10_000_000, 20);
    }

    @Test
    void 空数据返回空列表和total0() {
        when(reviewedMealReader.browse(0, 20)).thenReturn(List.of());
        when(reviewedMealReader.countPublic()).thenReturn(0);
        PagedResponse<MealBrowseItem> response = service.browse(1, 20);
        assertTrue(response.items().isEmpty());
        assertEquals(0, response.total());
        assertEquals(0, response.totalPages());
    }

    @Test
    void fixture模式返回能力不可用且不调用审核读取模块() {
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.providerMode()).thenReturn(ResourceMode.FIXTURE_SEED);
        MealBrowseService fixtureService = new MealBrowseService(reviewedMealReader, provider);

        HealthApiException error = assertThrows(HealthApiException.class,
                () -> fixtureService.browse(1, 20));

        assertEquals(HealthApiException.CODE_RESOURCE_MODE_UNAVAILABLE, error.code());
        assertTrue(error.getMessage().contains("餐食浏览"));
        verifyNoInteractions(reviewedMealReader);
    }

    @Test
    void reviewed模式正常调用审核读取模块() {
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.providerMode()).thenReturn(ResourceMode.REVIEWED_DB);
        when(reviewedMealReader.browse(0, 20)).thenReturn(List.of());
        when(reviewedMealReader.countPublic()).thenReturn(0);

        new MealBrowseService(reviewedMealReader, provider).browse(1, 20);

        verify(reviewedMealReader).browse(0, 20);
        verify(reviewedMealReader).countPublic();
        verify(provider, never()).planMealCandidates();
    }

    @Test
    void 分页总数与总页数正确() {
        when(reviewedMealReader.browse(0, 20)).thenReturn(List.of(meal()));
        when(reviewedMealReader.countPublic()).thenReturn(45);
        PagedResponse<MealBrowseItem> response = service.browse(1, 20);
        assertEquals(45, response.total());
        assertEquals(3, response.totalPages());
    }

    @Test
    void 字段映射完整_营养估算与过敏原状态透出() {
        when(reviewedMealReader.browse(0, 20)).thenReturn(List.of(meal()));
        when(reviewedMealReader.countPublic()).thenReturn(1);
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
        assertEquals(null, item.mediaUrl());
        assertEquals("NONE", item.mediaStatus());
        assertEquals("foodcom-recipes-and-reviews-v2", item.sourceName());
        assertEquals("317010", item.sourceId());
        assertEquals("v2", item.sourceVersion());
    }

    @Test
    void 只有已授权媒体下发地址() {
        when(reviewedMealReader.browse(0, 20)).thenReturn(List.of(
                meal("/assets/licensed.jpg", "LICENSED"),
                meal("/assets/unlicensed.jpg", "NONE")));
        when(reviewedMealReader.countPublic()).thenReturn(2);

        List<MealBrowseItem> items = service.browse(1, 20).items();

        assertEquals("/assets/licensed.jpg", items.get(0).mediaUrl());
        assertEquals(null, items.get(1).mediaUrl());
    }

    @Test
    void 槽位标签映射为七维map() {
        when(reviewedMealReader.browse(0, 20)).thenReturn(List.of(meal()));
        when(reviewedMealReader.countPublic()).thenReturn(1);
        Map<String, List<String>> tags = service.browse(1, 20).items().get(0).tags();

        assertEquals(List.of("早餐", "午餐"), tags.get("mealTime"));
        assertEquals(List.of("高蛋白"), tags.get("healthGoal"));
        assertEquals(List.of("中式"), tags.get("cuisine"));
        assertEquals(List.of("鲜"), tags.get("taste"));
        assertTrue(tags.containsKey("mood"));
        assertTrue(tags.containsKey("scene"));
        assertTrue(tags.containsKey("convenience"));
    }

    private ReviewedMeal meal() {
        return meal(null, "NONE");
    }

    private ReviewedMeal meal(String mediaUrl, String mediaStatus) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("mealTime", List.of("早餐", "午餐"));
        tags.put("mood", List.of());
        tags.put("scene", List.of());
        tags.put("healthGoal", List.of("高蛋白"));
        tags.put("cuisine", List.of("中式"));
        tags.put("taste", List.of("鲜"));
        tags.put("convenience", List.of());
        return new ReviewedMeal(
                100L,
                "番茄鸡蛋面",
                "Tomato Egg Noodles",
                List.of("番茄面"),
                tags,
                "清爽家常面",
                List.of("番茄", "鸡蛋", "面条"),
                new ReviewedMeal.Serving(4, new BigDecimal("1.00"), "份"),
                new ReviewedMeal.Nutrition(
                        new BigDecimal("245.00"), new BigDecimal("12.00"), new BigDecimal("8.00"),
                        new BigDecimal("30.00"), "foodcom_source_value", true),
                List.of("鸡蛋"),
                "REVIEWED",
                "APPROVED",
                mediaUrl,
                mediaStatus,
                null,
                "foodcom-recipes-and-reviews-v2",
                "317010",
                "v2",
                "PUBLIC"
        );
    }
}
