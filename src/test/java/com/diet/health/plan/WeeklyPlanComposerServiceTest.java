package com.diet.health.plan;

import com.diet.health.resource.SeedResourceProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 餐食子计划组合器只允许产生 MEAL，旧混合组合器已删除。 */
class WeeklyPlanComposerServiceTest {

    @Test
    void 餐食组合器不附加训练和作息() {
        MealPlanPicker picker = new MealPlanPicker(new SeedResourceProvider());
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(new SeedResourceProvider(), picker);

        List<PlanItemDraft> items = composer.composeMeals(1400, 1800, LocalDate.of(2026, 8, 17));

        assertFalse(items.isEmpty());
        assertTrue(items.stream().allMatch(PlanItemDraft::isMeal));
        assertTrue(items.stream().noneMatch(item -> item.isExercise() || item.isRoutine()));
    }

    @Test
    void 空餐食候选不会伪造其他范围项目() {
        MealPlanPicker picker = mock(MealPlanPicker.class);
        when(picker.pickForDay(anyInt(), anyInt())).thenReturn(List.of());
        WeeklyPlanComposerService composer = new WeeklyPlanComposerService(
                new SeedResourceProvider(), picker);

        assertTrue(composer.composeMeals(1400, 1800, LocalDate.of(2026, 8, 17)).isEmpty());
    }
}
