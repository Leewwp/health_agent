package com.diet.health.resource;

import com.diet.health.module.HealthResource;
import com.diet.health.module.PlanMealCandidate;
import com.diet.health.module.RoutineFact;
import com.diet.health.seed.SeedResources;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存种子 Provider 测试（fixture 模式）：
 * 仍返回 9001-9008 / R1-R5 / M1-M9，providerMode 与 resourceVersion 有明确标识，可与种子直连链路混用。
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
        assertTrue(provider.mealById("5").isEmpty(), "审核主键 ID 5 不在种子餐食中");
    }

    @Test
    void 按ID与主题关键词查询() {
        assertEquals("9001", provider.exerciseById("9001").orElseThrow().resourceId());
        assertTrue(provider.exerciseById("9999").isEmpty());
        assertEquals("R1", provider.routineFactById("R1").orElseThrow().factId());
        assertTrue(provider.routineFactById("R9").isEmpty());
        assertEquals("R1", provider.routineFactByTopic("睡眠时长").orElseThrow().factId(),
                "种子类别与主题关键词双向包含匹配应命中 R1");
        assertEquals("M1", provider.mealById("M1").orElseThrow().resourceId(), "fixture 模式餐食按种子 ID 解析");
        assertTrue(provider.mealById("1").isEmpty(), "数据库主键 ID 不属于种子餐食");
    }

    @Test
    void 餐食候选确定覆盖三餐且含每份热量() {
        List<PlanMealCandidate> candidates = provider.planMealCandidates();
        assertEquals(9, candidates.size(), "种子餐食候选固定 9 道");
        assertEquals(List.of("M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9"),
                candidates.stream().map(PlanMealCandidate::resourceId).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L),
                candidates.stream().map(PlanMealCandidate::sortKey).toList(), "sortKey 为种子列表序");
        assertTrue(candidates.stream().allMatch(candidate -> candidate.caloriesKcal() != null && candidate.caloriesKcal() > 0),
                "每道种子餐食必须含每份热量");
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.mealTimeTags().contains("早餐")),
                "种子餐食必须覆盖早餐");
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.mealTimeTags().contains("午餐")),
                "种子餐食必须覆盖午餐");
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.mealTimeTags().contains("晚餐")),
                "种子餐食必须覆盖晚餐");
        assertTrue(candidates.stream().allMatch(candidate -> "MEAL".equals(candidate.resourceType())));
    }

    @Test
    void 餐食候选与动作事实ID互斥() {
        List<String> mealIds = provider.planMealCandidates().stream()
                .map(PlanMealCandidate::resourceId).toList();
        assertTrue(mealIds.stream().noneMatch(id -> id.matches("900[1-8]|R[1-5]")),
                "餐食候选不得占用动作/事实种子 ID");
    }

    @Test
    void provider模式与资源版本标识明确() {
        assertEquals(ResourceMode.FIXTURE_SEED, provider.providerMode());
        assertEquals(SeedResources.SEED_VERSION, provider.resourceVersion());
    }
}
