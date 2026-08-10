package com.diet.health.module;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 健身种子模块：筛选、排序、plan_ready 与无图状态。 */
class ExerciseModuleTest {

    private final ExerciseModule module = new ExerciseModule();

    @Test
    void 按部位筛选返回匹配动作() {
        List<HealthResource> result = module.recommend(Map.of("bodyParts", List.of("胸")), List.of(), 5);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(item -> item.tags().get("bodyParts").contains("胸")));
        assertEquals("9001", result.get(0).resourceId());
    }

    @Test
    void 按器材筛选() {
        List<HealthResource> result = module.recommend(Map.of("equipment", List.of("弹力带")), List.of(), 5);
        assertTrue(result.stream().allMatch(item -> item.tags().get("equipment").contains("弹力带")));
    }

    @Test
    void 排除ID后不再出现() {
        List<HealthResource> result = module.recommend(Map.of("bodyParts", List.of("胸")), List.of(9001L), 5);
        assertFalse(result.stream().anyMatch(item -> item.resourceId().equals("9001")));
    }

    @Test
    void 全部种子动作planReady且无图() {
        module.listAll().forEach(item -> {
            assertEquals("EXERCISE", item.resourceType());
            assertTrue(item.planReady(), item.name() + " 应 planReady");
            assertNull(item.mediaUrl(), item.name() + " 应为无图状态");
            assertEquals("Gym visual", item.sourceName());
        });
    }
}
