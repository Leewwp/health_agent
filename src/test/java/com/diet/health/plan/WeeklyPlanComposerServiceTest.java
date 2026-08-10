package com.diet.health.plan;

import com.diet.health.module.HealthResource;
import com.diet.health.module.RoutineFact;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 确定性周计划组合器（34 号）：周一至周日、作息/三餐/训练落位，
 * 训练只使用 plan_ready 动作且周一/三/五部位不连续；资源来自统一审核 Provider，
 * Provider 空库时跳过作息与训练、仍可生成餐食计划。
 */
class WeeklyPlanComposerServiceTest {

    private static final LocalDate MON = LocalDate.of(2026, 8, 17);

    private final MealPlanPicker picker = mock(MealPlanPicker.class);
    private final WeeklyPlanComposerService composer =
            new WeeklyPlanComposerService(new SeedResourceProvider(), picker);

    private static List<MealPlanPicker.MealPick> threeMeals() {
        return List.of(
                new MealPlanPicker.MealPick("5", "清蒸鲈鱼", 400, "早餐"),
                new MealPlanPicker.MealPick("7", "鸡胸肉沙拉", 600, "午餐"),
                new MealPlanPicker.MealPick("9", "白灼西兰花", 400, "晚餐")
        );
    }

    @Test
    void 一周七天包含作息三餐与训练() {
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);

        long sleepCount = items.stream().filter(PlanItemDraft::isRoutine).count();
        long mealCount = items.stream().filter(PlanItemDraft::isMeal).count();
        long exerciseCount = items.stream().filter(PlanItemDraft::isExercise).count();
        assertEquals(7, sleepCount, "每天一条作息");
        assertEquals(21, mealCount, "每天三餐");
        assertEquals(3, exerciseCount, "周一/三/五训练");
        assertTrue(items.stream().allMatch(item -> !item.localDate().isBefore(MON) && !item.localDate().isAfter(MON.plusDays(6))));
    }

    @Test
    void 训练只安排在周一三五且部位不重复() {
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        List<PlanItemDraft> trainings = items.stream().filter(PlanItemDraft::isExercise).toList();
        assertEquals(List.of(MON, MON.plusDays(2), MON.plusDays(4)),
                trainings.stream().map(PlanItemDraft::localDate).toList());
        List<String> parts = trainings.stream().map(PlanItemDraft::bodyPart).toList();
        assertEquals(3, parts.stream().distinct().count(), "训练部位不得重复");
        assertTrue(trainings.stream().allMatch(item -> item.startTime().getHour() == 19));
    }

    @Test
    void 训练焦点偏好优先安排对应部位() {
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", "背");
        PlanItemDraft first = items.stream().filter(PlanItemDraft::isExercise)
                .min(java.util.Comparator.comparing(PlanItemDraft::localDate)).orElseThrow();
        assertEquals("背", first.bodyPart());
        assertEquals("弹力带划船", first.name());
    }

    @Test
    void 餐食热量进入计划参数() {
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        PlanItemDraft lunch = items.stream()
                .filter(item -> item.isMeal() && "午餐".equals(item.planParams().get("mealTime")))
                .findFirst().orElseThrow();
        assertEquals(600, lunch.caloriesKcal());
        assertTrue(lunch.planParams().containsKey("mealTime"));
    }

    @Test
    void 作息项目每天固定睡眠时段fixture模式引用R1() {
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        PlanItemDraft sleep = items.stream().filter(PlanItemDraft::isRoutine).findFirst().orElseThrow();
        assertEquals("R1", sleep.resourceId(), "fixture 模式睡眠事实为种子 R1");
        assertEquals(23, sleep.startTime().getHour());
        assertEquals(7, sleep.endTime().getHour());
    }

    @Test
    void 数据库模式作息项目使用审核子集事实refId() {
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.routineFactByTopic("睡眠时长")).thenReturn(Optional.of(
                new RoutineFact("aasm-sleep-minimum", "睡眠时长下限", "健康成人应保证每晚至少 7 小时睡眠", "AASM 共识", "成人 18+")));
        when(provider.planReadyExercises()).thenReturn(seedPlanReady());
        WeeklyPlanComposerService dbComposer = new WeeklyPlanComposerService(provider, picker);
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());

        List<PlanItemDraft> items = dbComposer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        PlanItemDraft sleep = items.stream().filter(PlanItemDraft::isRoutine).findFirst().orElseThrow();
        assertEquals("aasm-sleep-minimum", sleep.resourceId(), "数据库模式睡眠项目引用审核子集 ref_id");
        assertTrue(items.stream().allMatch(item -> !"9001".equals(item.resourceId()) && !"R1".equals(item.resourceId())),
                "数据库模式不得出现种子 ID");
    }

    @Test
    void 无餐食候选时只保留作息与训练() {
        when(picker.pickForDay(1400, 1800)).thenReturn(List.of());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        assertEquals(0, items.stream().filter(PlanItemDraft::isMeal).count());
        assertEquals(7, items.stream().filter(PlanItemDraft::isRoutine).count());
        assertEquals(3, items.stream().filter(PlanItemDraft::isExercise).count());
    }

    @Test
    void 无planReady动作时训练为空() {
        HealthResourceProvider provider = mock(HealthResourceProvider.class);
        when(provider.routineFactByTopic(any())).thenReturn(Optional.of(
                new RoutineFact("R1", "睡眠", "成人每晚 7-9 小时", "来源", "07-09h")));
        when(provider.planReadyExercises()).thenReturn(List.of());
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(provider, picker);
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        assertEquals(0, items.stream().filter(PlanItemDraft::isExercise).count());
        assertEquals(7, items.stream().filter(PlanItemDraft::isRoutine).count());
        assertFalse(items.isEmpty());
    }

    @Test
    void 空库时作息与训练跳过仍可生成餐食计划() {
        HealthResourceProvider empty = mock(HealthResourceProvider.class);
        when(empty.routineFactByTopic(any())).thenReturn(Optional.empty());
        when(empty.planReadyExercises()).thenReturn(List.of());
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(empty, picker);
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        assertEquals(0, items.stream().filter(PlanItemDraft::isRoutine).count(), "空库无作息事实时跳过作息项目");
        assertEquals(0, items.stream().filter(PlanItemDraft::isExercise).count(), "空库无 plan_ready 动作时跳过训练项目");
        assertEquals(21, items.stream().filter(PlanItemDraft::isMeal).count(), "仍可生成三餐计划");
    }

    @Test
    void 睡眠跨午夜不影响其他时间段() {
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        PlanItemDraft exercise = items.stream().filter(PlanItemDraft::isExercise).findFirst().orElseThrow();
        assertTrue(exercise.startTime().isAfter(java.time.LocalTime.of(7, 0)));
        assertTrue(exercise.endTime().isBefore(java.time.LocalTime.of(23, 0)));
    }

    /** 与种子 plan_ready 动作等价的桩数据（数据库模式组合器用）。 */
    private static List<HealthResource> seedPlanReady() {
        return List.of(
                new HealthResource("EXERCISE", "101", "俯卧撑", "DATASET", "gym-visual-exercises-dataset", null, true,
                        Map.of("primaryBodyPart", List.of("胸"), "bodyParts", List.of("胸", "手臂"), "equipment", List.of("徒手"))),
                new HealthResource("EXERCISE", "102", "深蹲", "DATASET", "gym-visual-exercises-dataset", null, true,
                        Map.of("primaryBodyPart", List.of("腿"), "bodyParts", List.of("腿"), "equipment", List.of("徒手"))),
                new HealthResource("EXERCISE", "103", "平板支撑", "DATASET", "gym-visual-exercises-dataset", null, true,
                        Map.of("primaryBodyPart", List.of("核心"), "bodyParts", List.of("核心"), "equipment", List.of("徒手")))
        );
    }
}
