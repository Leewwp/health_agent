package com.diet.health.plan;

import com.diet.health.module.ExerciseModule;
import com.diet.health.module.HealthResource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 确定性周计划组合器（34 号）：周一至周日、作息/三餐/训练落位，
 * 训练只使用 plan_ready 动作且周一/三/五部位不连续；无餐食或动作时降级为空。
 */
class WeeklyPlanComposerServiceTest {

    private static final LocalDate MON = LocalDate.of(2026, 8, 17);

    private final MealPlanPicker picker = mock(MealPlanPicker.class);
    private final ExerciseModule exerciseModule = new ExerciseModule();
    private final WeeklyPlanComposerService composer = new WeeklyPlanComposerService(exerciseModule, picker);

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
    void 作息项目每天固定睡眠时段() {
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        PlanItemDraft sleep = items.stream().filter(PlanItemDraft::isRoutine).findFirst().orElseThrow();
        assertEquals("R1", sleep.resourceId());
        assertEquals(23, sleep.startTime().getHour());
        assertEquals(7, sleep.endTime().getHour());
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
        ExerciseModule empty = mock(ExerciseModule.class);
        when(empty.listAll()).thenReturn(List.of());
        when(empty.recommend(any(), any(), anyInt())).thenReturn(List.of());
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(empty, picker);
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        assertEquals(0, items.stream().filter(PlanItemDraft::isExercise).count());
        assertEquals(7, items.stream().filter(PlanItemDraft::isRoutine).count());
        assertFalse(items.isEmpty());
    }

    @Test
    void 睡眠跨午夜不影响其他时间段() {
        when(picker.pickForDay(1400, 1800)).thenReturn(threeMeals());
        List<PlanItemDraft> items = composer.compose(1400, 1800, MON, "Asia/Shanghai", null);
        PlanItemDraft exercise = items.stream().filter(PlanItemDraft::isExercise).findFirst().orElseThrow();
        assertTrue(exercise.startTime().isAfter(java.time.LocalTime.of(7, 0)));
        assertTrue(exercise.endTime().isBefore(java.time.LocalTime.of(23, 0)));
    }
}
