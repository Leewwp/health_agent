package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.PlanScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 计划范围 Guard 的严格类型集合契约。 */
class PlanScopeGuardTest {

    private final PlanScopeGuard guard = new PlanScopeGuard();

    @Test
    void 综合计划允许餐食训练任一集合但不得包含作息() {
        guard.requireCompatible(PlanScope.COMPOSITE, List.of(exercise(), meal()));
        guard.requireCompatible(PlanScope.COMPOSITE, List.of(exercise()));
        guard.requireCompatible(PlanScope.COMPOSITE, List.of(meal()));
        assertThrows(HealthApiException.class, () -> guard.requireCompatible(
                PlanScope.COMPOSITE, List.of(exercise(), routine())));
    }

    @Test
    void 训练和餐食范围拒绝错误类型() {
        HealthApiException exerciseError = assertThrows(HealthApiException.class,
                () -> guard.requireCompatible(PlanScope.EXERCISE, List.of(meal())));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, exerciseError.code());
        assertThrows(HealthApiException.class, () -> guard.requireCompatible(PlanScope.MEAL, List.of(exercise())));
    }

    private PlanItemDraft exercise() {
        return new PlanItemDraft("EXERCISE", "9001", "俯卧撑", LocalDate.of(2026, 8, 17),
                LocalTime.of(19, 0), LocalTime.of(20, 0), null, Map.of("bodyPart", "胸"));
    }

    private PlanItemDraft meal() {
        return new PlanItemDraft("MEAL", "M1", "早餐", LocalDate.of(2026, 8, 17),
                LocalTime.of(8, 0), LocalTime.of(8, 30), null, Map.of("caloriesKcal", 400));
    }

    private PlanItemDraft routine() {
        return new PlanItemDraft("ROUTINE", "R1", "睡眠", LocalDate.of(2026, 8, 17),
                LocalTime.of(23, 0), LocalTime.of(7, 0), null, Map.of());
    }
}
