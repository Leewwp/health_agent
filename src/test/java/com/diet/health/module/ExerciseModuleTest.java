package com.diet.health.module;

import com.diet.health.feedback.PreferenceService;
import com.diet.health.resource.DbReviewedResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import com.diet.mapper.ExerciseMapper;
import com.diet.mapper.FeedbackMapper;
import com.diet.mapper.MealMapper;
import com.diet.mapper.RoutineFactMapper;
import com.diet.model.ExerciseItemRow;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 健身种子模块：筛选、排序、plan_ready 与无图状态（fixture 模式 Provider）。 */
class ExerciseModuleTest {

    private final ExerciseModule module = new ExerciseModule(
            new SeedResourceProvider(), new PreferenceService(mock(FeedbackMapper.class)));

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
        List<HealthResource> result = module.recommend(Map.of("bodyParts", List.of("胸")), List.of("9001"), 5);
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

    @Test
    void 全身筛选召回cardio有氧动作() {
        // 数据库审核 Provider + 数据集真实 cardio 动作行（0630 登山者 / 1160 波比跳 / 0662 俯卧撑）
        ExerciseMapper exerciseMapper = mock(ExerciseMapper.class);
        MealMapper mealMapper = mock(MealMapper.class);
        RoutineFactMapper factMapper = mock(RoutineFactMapper.class);
        when(exerciseMapper.findAllApproved()).thenReturn(List.of(
                dbRow(630L, "登山者", "cardio", "[\"core\"]", "[\"core\", \"shoulders\", \"triceps\"]"),
                dbRow(1160L, "波比跳", "cardio", "[\"quadriceps\"]", "[\"quadriceps\", \"hamstrings\", \"calves\", \"shoulders\", \"chest\"]"),
                dbRow(662L, "俯卧撑", "chest", "[\"triceps\"]", "[\"triceps\", \"deltoids\", \"core\"]")
        ));
        when(exerciseMapper.findAllCatalog()).thenReturn(List.of(
                dbRow(630L, "登山者", "cardio", "[\"core\"]", "[\"core\", \"shoulders\", \"triceps\"]"),
                dbRow(1160L, "波比跳", "cardio", "[\"quadriceps\"]", "[\"quadriceps\", \"hamstrings\", \"calves\", \"shoulders\", \"chest\"]"),
                dbRow(662L, "俯卧撑", "chest", "[\"triceps\"]", "[\"triceps\", \"deltoids\", \"core\"]")
        ));
        ExerciseModule dbModule = new ExerciseModule(
                new DbReviewedResourceProvider(exerciseMapper, mealMapper, factMapper,
                        new JsonService(new ObjectMapper())),
                new PreferenceService(mock(FeedbackMapper.class)));

        List<HealthResource> result = dbModule.recommend(Map.of("bodyParts", List.of("全身")), List.of(), 5);

        assertTrue(result.stream().anyMatch(item -> item.name().equals("登山者")), "「全身」应召回登山者");
        assertTrue(result.stream().anyMatch(item -> item.name().equals("波比跳")), "「全身」应召回波比跳");
        assertTrue(result.stream().noneMatch(item -> item.name().equals("俯卧撑")), "「全身」不应召回胸部动作");
        assertTrue(result.stream().allMatch(item -> item.tags().get("bodyParts").contains("全身")),
                "召回动作的 bodyParts 都必须含「全身」（归一后的中文值参与比较）");
    }

    @Test
    void 完整目录进入单次推荐但不改变计划资格边界() {
        ExerciseMapper exerciseMapper = mock(ExerciseMapper.class);
        MealMapper mealMapper = mock(MealMapper.class);
        RoutineFactMapper factMapper = mock(RoutineFactMapper.class);
        ExerciseItemRow pending = dbRow(1531L, "目录待审核动作", "chest", "[\"triceps\"]", "[]");
        pending.setReviewStatus("PENDING");
        pending.setPlanReady(false);
        when(exerciseMapper.findAllCatalog()).thenReturn(List.of(pending));
        when(exerciseMapper.findAllApproved()).thenReturn(List.of());
        DbReviewedResourceProvider provider = new DbReviewedResourceProvider(
                exerciseMapper, mealMapper, factMapper, new JsonService(new ObjectMapper()));

        ExerciseModule module = new ExerciseModule(provider,
                new PreferenceService(mock(FeedbackMapper.class)));

        List<HealthResource> recommendations = module.recommend(
                Map.of("bodyParts", List.of("胸")), List.of(), 5);

        assertEquals(List.of("1531"), recommendations.stream().map(HealthResource::resourceId).toList());
        assertTrue(provider.planReadyExercises().isEmpty(), "待审核目录动作不得进入计划候选");
    }

    private static ExerciseItemRow dbRow(Long id, String name, String bodyPart, String target, String secondary) {
        ExerciseItemRow row = new ExerciseItemRow();
        row.setId(id);
        row.setName(name);
        row.setSourceName("gym-visual-exercises-dataset");
        row.setSourceId(String.valueOf(id));
        row.setSourceVersion("main-2026-08-10");
        row.setBodyPart(bodyPart);
        row.setTargetMuscles(target);
        row.setSecondaryMuscles(secondary);
        row.setEquipment("body weight");
        row.setDifficulty("中级");
        row.setMovementPattern("有氧");
        row.setPlanReady(true);
        return row;
    }
}
