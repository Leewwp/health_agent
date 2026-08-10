package com.diet.health.resource;

import com.diet.health.module.HealthResource;
import com.diet.health.module.RoutineFact;
import com.diet.health.seed.SeedResources;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存种子 Provider 测试（fixture 模式）：
 * 仍返回 9001-9008 / R1-R5，providerMode 与 resourceVersion 有明确标识，可与种子直连链路混用。
 */
class SeedResourceProviderTest {

    private final SeedResourceProvider provider = new SeedResourceProvider();

    @Test
    void 返回种子动作与事实ID集合() {
        List<HealthResource> exercises = provider.exercises();
        assertEquals(8, exercises.size());
        assertTrue(exercises.stream().allMatch(item -> item.resourceId().matches("900[1-8]")));
        assertTrue(provider.planReadyExerciseIds().containsAll(List.of("9001", "9002", "9003", "9004", "9005", "9006", "9007", "9008")));
        List<RoutineFact> facts = provider.routineFacts();
        assertEquals(5, facts.size());
        assertEquals(List.of("R1", "R2", "R3", "R4", "R5"), provider.allFactIds());
        assertTrue(provider.mealById("5").isEmpty(), "fixture 模式无餐食");
    }

    @Test
    void 按ID与主题关键词查询() {
        assertEquals("9001", provider.exerciseById("9001").orElseThrow().resourceId());
        assertTrue(provider.exerciseById("9999").isEmpty());
        assertEquals("R1", provider.routineFactById("R1").orElseThrow().factId());
        assertTrue(provider.routineFactById("R9").isEmpty());
        assertEquals("R1", provider.routineFactByTopic("睡眠时长").orElseThrow().factId(),
                "种子类别与主题关键词双向包含匹配应命中 R1");
        assertTrue(provider.mealById("1").isEmpty());
    }

    @Test
    void provider模式与资源版本标识明确() {
        assertEquals("FIXTURE_SEED", provider.providerMode());
        assertEquals(SeedResources.SEED_VERSION, provider.resourceVersion());
    }
}
