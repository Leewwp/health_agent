package com.diet.health.module;

import com.diet.enums.SourceMode;
import com.diet.model.MealItem;
import com.diet.model.SlotBundle;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 餐食模块：个人空库在模块内处理 + 健康链路映射。 */
class MealModuleTest {

    private final MealSearchService search = mock(MealSearchService.class);
    private final MealModule module = new MealModule(search, new MealRankService(), mock(AgentTraceService.class));

    @Test
    void PERSONAL空库返回空候选不抛异常() {
        when(search.search(any())).thenReturn(List.of());
        List<MealItem> result = module.searchAndRank(SourceMode.PERSONAL, 1L, SlotBundle.empty(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void 健康链路PUBLIC检索映射为类型化资源() {
        MealItem item = new MealItem(5L, SourceMode.PUBLIC, null, "清蒸鲈鱼",
                new SlotBundle(List.of("午餐"), List.of(), List.of(), List.of("清淡"), List.of(), List.of(), List.of()), 0.9);
        when(search.search(any())).thenReturn(List.of(item));
        List<HealthResource> resources = module.recommendMeals(
                Map.of("mealTime", List.of("午餐"), "healthGoal", List.of("清淡")), List.of());
        assertEquals(1, resources.size());
        HealthResource resource = resources.get(0);
        assertEquals("MEAL", resource.resourceType());
        assertEquals("5", resource.resourceId());
        assertEquals("清蒸鲈鱼", resource.name());
        assertTrue(resource.tags().get("healthGoal").contains("清淡"));
    }

    @Test
    void 健康槽位映射到旧SlotBundle() {
        SlotBundle bundle = module.toSlotBundle(Map.of("mealTime", List.of("午餐"), "bodyParts", List.of("胸")));
        assertEquals(List.of("午餐"), bundle.mealTime());
        assertTrue(bundle.mood().isEmpty());
    }
}
