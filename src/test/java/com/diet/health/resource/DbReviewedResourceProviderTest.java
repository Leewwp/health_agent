package com.diet.health.resource;

import com.diet.health.TestSupport;
import com.diet.health.browse.ExerciseBrowseService;
import com.diet.health.intent.HealthSlotDictionary;
import com.diet.health.module.HealthResource;
import com.diet.health.module.PlanMealCandidate;
import com.diet.health.module.RoutineFact;
import com.diet.health.model.ExerciseBrowseItem;
import com.diet.health.model.PagedResponse;
import com.diet.health.reader.exercise.DbReviewedExerciseReader;
import com.diet.mapper.ExerciseMapper;
import com.diet.mapper.MealMapper;
import com.diet.mapper.RoutineFactMapper;
import com.diet.model.ExerciseItemRow;
import com.diet.model.MealItemRow;
import com.diet.model.RoutineFactRow;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据库审核子集 Provider 测试（40 号票）：
 * 与审核子集数量一致、与浏览 API 的 resourceId 一致、plan_ready 过滤、
 * 器材/部位翻译、主题关键词命中与空库降级。
 */
class DbReviewedResourceProviderTest {

    private static final int APPROVED_MEALS = 295;
    private static final int APPROVED_EXERCISES = 30;
    private static final int ROUTINE_FACTS = 15;

    private final ExerciseMapper exerciseMapper = mock(ExerciseMapper.class);
    private final MealMapper mealMapper = mock(MealMapper.class);
    private final RoutineFactMapper factMapper = mock(RoutineFactMapper.class);
    private final JsonService jsonService = new JsonService(new ObjectMapper());
    private DbReviewedResourceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DbReviewedResourceProvider(exerciseMapper, mealMapper, factMapper, jsonService);
    }

    @Test
    void 动作数量与审核子集一致且ID为主键字符串() {
        when(exerciseMapper.findAllApproved()).thenReturn(exerciseRows(APPROVED_EXERCISES));
        List<HealthResource> exercises = provider.exercises();
        assertEquals(APPROVED_EXERCISES, exercises.size());
        assertEquals("1", exercises.get(0).resourceId());
        assertEquals("30", exercises.get(29).resourceId());
        assertTrue(exercises.stream().allMatch(item -> "EXERCISE".equals(item.resourceType())));
    }

    @Test
    void 餐食按主键查询与审核子集一致() {
        when(mealMapper.findApprovedPublicById(5L)).thenReturn(mealRows(APPROVED_MEALS).get(4));
        HealthResource meal = provider.mealById("5").orElseThrow();
        assertEquals("MEAL", meal.resourceType());
        assertEquals("5", meal.resourceId());
        assertTrue(provider.mealById("9999").isEmpty());
    }

    @Test
    void 只有已授权媒体进入健康资源() {
        ExerciseItemRow exercise = exerciseRows(1).get(0);
        exercise.setMediaUrl("/assets/exercise.gif");
        exercise.setMediaState("NONE");
        when(exerciseMapper.findAllApproved()).thenReturn(List.of(exercise));
        assertEquals(null, provider.exercises().get(0).mediaUrl());

        exercise.setMediaState("LICENSED");
        assertEquals("/assets/exercise.gif", provider.exercises().get(0).mediaUrl());

        MealItemRow meal = mealRows(1).get(0);
        meal.setMediaUrl("/assets/meal.jpg");
        meal.setMediaStatus("NONE");
        when(mealMapper.findApprovedPublicById(1L)).thenReturn(meal);
        assertEquals(null, provider.mealById("1").orElseThrow().mediaUrl());

        meal.setMediaStatus("LICENSED");
        assertEquals("/assets/meal.jpg", provider.mealById("1").orElseThrow().mediaUrl());
    }

    @Test
    void 计划餐食候选与浏览公共餐食同源且按主键序() {
        List<MealItemRow> rows = mealRows(5);
        rows.get(0).setCaloriesKcal(java.math.BigDecimal.valueOf(320));
        rows.get(1).setCaloriesKcal(java.math.BigDecimal.valueOf(750));
        rows.get(2).setCaloriesKcal(java.math.BigDecimal.valueOf(450));
        rows.get(3).setCaloriesKcal(java.math.BigDecimal.valueOf(600));
        rows.get(4).setCaloriesKcal(java.math.BigDecimal.valueOf(900));
        when(mealMapper.findApprovedPublicMeals()).thenReturn(rows);

        List<PlanMealCandidate> candidates = provider.planMealCandidates();
        assertEquals(List.of("1", "2", "3", "4", "5"), candidates.stream().map(PlanMealCandidate::resourceId).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), candidates.stream().map(PlanMealCandidate::sortKey).toList(),
                "sortKey 必须与数据库主键序一致（挑选确定性依据）");
        assertEquals(List.of(320, 750, 450, 600, 900),
                candidates.stream().map(PlanMealCandidate::caloriesKcal).toList());
        assertEquals(List.of("午餐"), candidates.get(1).mealTimeTags(), "餐次标签从 meal_time JSON 解析");
        assertTrue(candidates.stream().allMatch(candidate -> "MEAL".equals(candidate.resourceType())));
    }

    @Test
    void 计划餐食候选无热量口径保留null且空库返回空() {
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(mealRows(1).get(0)));
        assertTrue(provider.planMealCandidates().get(0).caloriesKcal() == null,
                "热量缺失保留 null，由挑选器按既有降级策略过滤");

        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of());
        assertTrue(provider.planMealCandidates().isEmpty(), "空库返回空集合不抛异常");
    }

    @Test
    void 畸形餐次JSON按空标签全时段降级不抛异常() {
        MealItemRow row = mealRows(1).get(0);
        row.setMealTime("[not-json");
        when(mealMapper.findApprovedPublicMeals()).thenReturn(List.of(row));
        PlanMealCandidate candidate = provider.planMealCandidates().get(0);
        assertTrue(candidate.mealTimeTags().isEmpty(),
                "畸形餐次 JSON 按空标签（全时段可用）降级，不因单行脏数据中断计划生成");
    }

    @Test
    void 作息事实数量与审核子集一致且refId为业务键() {
        when(factMapper.selectAll()).thenReturn(factRows(ROUTINE_FACTS));
        List<RoutineFact> facts = provider.routineFacts();
        assertEquals(ROUTINE_FACTS, facts.size());
        assertEquals("aasm-sleep-minimum", facts.get(0).factId());
        assertEquals("睡眠时长下限", facts.get(0).category());
        assertEquals("成人 18+", facts.get(0).sourceDetail());
    }

    @Test
    void 动作ID与浏览API一致() {
        when(exerciseMapper.findAllApproved()).thenReturn(exerciseRows(5));
        when(exerciseMapper.browse(0, 50)).thenReturn(exerciseRows(5));
        when(exerciseMapper.count()).thenReturn(5);
        ExerciseBrowseService browse = new ExerciseBrowseService(
                new DbReviewedExerciseReader(exerciseMapper, jsonService), provider);
        PagedResponse<ExerciseBrowseItem> page = browse.browse(1, 50);
        List<String> browseIds = page.items().stream().map(item -> String.valueOf(item.id())).toList();
        List<String> providerIds = provider.exercises().stream().map(HealthResource::resourceId).toList();
        assertEquals(browseIds, providerIds, "浏览与 Provider 必须使用同一套 resourceId（数据库主键）");
    }

    @Test
    void planReady过滤只保留合格动作() {
        List<ExerciseItemRow> rows = exerciseRows(4);
        rows.get(1).setPlanReady(false);
        rows.get(3).setPlanReady(false);
        when(exerciseMapper.findAllApproved()).thenReturn(rows);
        assertEquals(List.of("1", "3"), provider.planReadyExerciseIds());
        assertEquals(2, provider.planReadyExercises().size());
        assertTrue(provider.planReadyExercises().stream().allMatch(HealthResource::planReady));
    }

    @Test
    void 器材与部位翻译为健身槽位中文词汇() {
        when(exerciseMapper.findAllApproved()).thenReturn(List.of(exerciseRow(1L, "chest",
                "[\"triceps\"]", "[\"triceps\", \"deltoids\", \"core\"]", "body weight")));
        HealthResource item = provider.exercises().get(0);
        assertEquals(List.of("徒手"), item.tags().get("equipment"));
        assertEquals(List.of("胸"), item.tags().get("primaryBodyPart"));
        assertTrue(item.tags().get("bodyParts").contains("胸"));
        assertTrue(item.tags().get("bodyParts").contains("手臂"));
        assertTrue(item.tags().get("bodyParts").contains("核心"));
        assertEquals("gym-visual-exercises-dataset", item.sourceName());
    }

    @Test
    void 主题关键词命中对应事实按ID升序返回第一条() {
        when(factMapper.selectByTopicLike("睡眠时长")).thenReturn(factRows(2));
        assertEquals("aasm-sleep-minimum", provider.routineFactByTopic("睡眠时长").orElseThrow().factId());
        when(factMapper.selectByTopicLike("咖啡")).thenReturn(List.of(factRow("caffeine-6h-cutoff", "咖啡因")));
        assertEquals("caffeine-6h-cutoff", provider.routineFactByTopic("咖啡").orElseThrow().factId());
        assertTrue(provider.routineFactByTopic("不存在的主题").isEmpty());
    }

    @Test
    void 按ID查询动作餐食事实() {
        when(exerciseMapper.findById(1L)).thenReturn(exerciseRows(1).get(0));
        when(mealMapper.findApprovedPublicById(5L)).thenReturn(mealRows(5).get(4));
        when(factMapper.selectByRefId("aasm-sleep-minimum")).thenReturn(factRows(1).get(0));
        assertEquals("1", provider.exerciseById("1").orElseThrow().resourceId());
        assertEquals("5", provider.mealById("5").orElseThrow().resourceId());
        assertEquals("aasm-sleep-minimum", provider.routineFactById("aasm-sleep-minimum").orElseThrow().factId());
        assertTrue(provider.exerciseById("abc").isEmpty(), "非数字 ID 返回空");
        assertTrue(provider.mealById("9999").isEmpty());
    }

    @Test
    void 空库返回空集合不抛异常() {
        when(exerciseMapper.findAllApproved()).thenReturn(List.of());
        when(factMapper.selectAll()).thenReturn(List.of());
        when(factMapper.selectByTopicLike(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        assertTrue(provider.exercises().isEmpty());
        assertTrue(provider.routineFacts().isEmpty());
        assertTrue(provider.planReadyExercises().isEmpty());
        assertTrue(provider.allFactIds().isEmpty());
        assertTrue(provider.routineFactByTopic("睡眠时长").isEmpty());
    }

    @Test
    void provider模式与资源版本标识明确() {
        assertEquals(ResourceMode.REVIEWED_DB, provider.providerMode());
        assertEquals("reviewed-2026-08-10-v1", provider.resourceVersion());
    }

    @Test
    void cardio动作在两个字段均归一为全身且不泄漏英文() {
        // 数据集 0630「登山者」：body_part=cardio，靶肌/次肌 core、shoulders、triceps
        ExerciseItemRow row = exerciseRow(630L, "cardio",
                "[\"core\"]", "[\"core\", \"shoulders\", \"triceps\"]", "body weight");
        row.setName("登山者");
        row.setSourceId("0630");
        when(exerciseMapper.findAllApproved()).thenReturn(List.of(row));
        HealthResource item = provider.exercises().get(0);

        assertTrue(item.tags().get("bodyParts").contains("全身"), "bodyParts 必须含「全身」");
        assertFalse(item.tags().get("bodyParts").contains("cardio"), "bodyParts 不得泄漏英文 cardio");
        assertEquals(List.of("全身"), item.tags().get("primaryBodyPart"), "primaryBodyPart 与 bodyParts 归一一致");
        assertEquals(List.of("徒手"), item.tags().get("equipment"));
        assertEquals(List.of("入门"), item.tags().get("difficulty"));
    }

    @Test
    void 数据集全部body_part原始值归一中文且健身槽位输出全部合法() {
        Map<String, String> bodyPartToZh = Map.of(
                "chest", "胸",
                "waist", "核心",
                "back", "背",
                "upper legs", "腿",
                "lower legs", "腿",
                "upper arms", "手臂",
                "shoulders", "肩",
                "cardio", "全身");
        Map<String, List<String>> legal = new HealthSlotDictionary(TestSupport.slotOptionService()).legalValues();
        for (Map.Entry<String, String> entry : bodyPartToZh.entrySet()) {
            ExerciseItemRow row = exerciseRow(100L, entry.getKey(),
                    "[\"triceps\"]", "[\"triceps\", \"core\"]", "body weight");
            row.setSourceId("raw-" + entry.getKey());
            when(exerciseMapper.findAllApproved()).thenReturn(List.of(row));
            HealthResource item = provider.exercises().get(0);

            assertEquals(List.of(entry.getValue()), item.tags().get("primaryBodyPart"),
                    entry.getKey() + " 的 primaryBodyPart 应归一为 " + entry.getValue());
            assertFalse(item.tags().get("bodyParts").isEmpty(), entry.getKey() + " 的 bodyParts 不应为空");
            assertFalse(String.join(",", item.tags().get("bodyParts")).matches(".*[a-zA-Z].*"),
                    entry.getKey() + " 的 bodyParts 不得泄漏英文原始值: " + item.tags().get("bodyParts"));

            for (String slot : List.of("bodyParts", "equipment", "difficulty")) {
                for (String value : item.tags().get(slot)) {
                    assertTrue(legal.get(slot).contains(value),
                            entry.getKey() + " 输出非法健身槽位值 " + slot + "=" + value);
                }
            }
        }
    }

    @Test
    void 未收录原始值过滤不透出英文() {
        ExerciseItemRow row = exerciseRow(200L, "forehead",
                "[\"unknown muscle\"]", "[]", "treadmill");
        row.setDifficulty("极限");
        when(exerciseMapper.findAllApproved()).thenReturn(List.of(row));
        HealthResource item = provider.exercises().get(0);

        assertEquals(List.of(), item.tags().get("bodyParts"), "未收录部位/肌群应过滤，不透出英文");
        assertEquals(List.of(), item.tags().get("primaryBodyPart"));
        assertEquals(List.of(), item.tags().get("equipment"), "未收录器材应过滤，不透出英文");
        assertEquals(List.of(), item.tags().get("difficulty"), "未收录难度应过滤");
        assertTrue(item.tags().values().stream().flatMap(List::stream).noneMatch(v -> v.matches(".*[a-zA-Z].*")),
                "对外资源不得含英文原始值: " + item.tags());
    }

    // ---- 测试数据 ----

    private static List<ExerciseItemRow> exerciseRows(int count) {
        java.util.ArrayList<ExerciseItemRow> rows = new java.util.ArrayList<>();
        for (long i = 1; i <= count; i++) {
            rows.add(exerciseRow(i, "chest", "[\"triceps\"]", "[\"triceps\", \"core\"]", "body weight"));
        }
        return rows;
    }

    private static ExerciseItemRow exerciseRow(Long id, String bodyPart, String target, String secondary, String equipment) {
        ExerciseItemRow row = new ExerciseItemRow();
        row.setId(id);
        row.setName("动作" + id);
        row.setSourceName("gym-visual-exercises-dataset");
        row.setSourceId(String.valueOf(1000 + id));
        row.setSourceVersion("main-2026-08-10");
        row.setBodyPart(bodyPart);
        row.setTargetMuscles(target);
        row.setSecondaryMuscles(secondary);
        row.setEquipment(equipment);
        row.setDifficulty("入门");
        row.setMovementPattern("推");
        row.setPlanReady(true);
        return row;
    }

    private static List<MealItemRow> mealRows(int count) {
        java.util.ArrayList<MealItemRow> rows = new java.util.ArrayList<>();
        for (long i = 1; i <= count; i++) {
            MealItemRow row = new MealItemRow();
            row.setId(i);
            row.setSourceType("PUBLIC");
            row.setName("餐食" + i);
            row.setMealTime("[\"午餐\"]");
            row.setHealthGoal("[\"清淡\"]");
            rows.add(row);
        }
        return rows;
    }

    private static List<RoutineFactRow> factRows(int count) {
        java.util.ArrayList<RoutineFactRow> rows = new java.util.ArrayList<>();
        rows.add(factRow("aasm-sleep-minimum", "睡眠时长下限"));
        for (long i = 2; i <= count; i++) {
            rows.add(factRow("ref-" + i, "topic-" + i));
        }
        return rows;
    }

    private static RoutineFactRow factRow(String refId, String topic) {
        RoutineFactRow row = new RoutineFactRow();
        row.setRefId(refId);
        row.setTopic(topic);
        row.setFactZh("健康成人应保证每晚至少 7 小时睡眠");
        row.setScope("成人 18+");
        row.setSource("美国睡眠医学会");
        row.setSourceVersion("2015");
        return row;
    }
}
