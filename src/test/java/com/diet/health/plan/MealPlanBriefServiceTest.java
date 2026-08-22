package com.diet.health.plan;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 餐食计划简报必须独立于训练字段，并要求单独确认。 */
class MealPlanBriefServiceTest {

    private final MealPlanBriefService service = new MealPlanBriefService();

    @Test
    void 餐食简报解析目标周和餐次并单独确认() {
        MealPlanBriefService.UpdateResult collected = service.update(MealPlanBrief.empty(),
                "下周安排早餐、午餐和晚餐，想减脂");

        assertEquals(List.of("早餐", "午餐", "晚餐"), collected.brief().mealTimes());
        assertEquals(DayOfWeek.MONDAY, collected.brief().weekStart().getDayOfWeek());
        assertTrue(collected.brief().isComplete());
        assertFalse(collected.brief().confirmed());

        MealPlanBriefService.UpdateResult confirmed = service.update(collected.brief(), "确认餐食计划");
        assertTrue(confirmed.confirmedNow());
        assertTrue(confirmed.brief().isConfirmedAndComplete());
        assertEquals(1, confirmed.brief().confirmationVersion());
    }

    @Test
    void 不完整餐食简报给字段指引且普通训练输入不写入() {
        MealPlanBriefService.UpdateResult partial = service.update(MealPlanBrief.empty(), "下周餐食计划");
        assertFalse(partial.brief().isComplete());
        assertTrue(partial.missingFields().contains("mealTimes"));
        assertTrue(partial.missingFields().contains("healthGoal"));
        assertTrue(partial.guidance().contains("餐次"));

        MealPlanBriefService.UpdateResult unrelated = service.update(partial.brief(), "我想练胸和背");
        assertEquals(BriefInterpretationStatus.UNRELATED, unrelated.status());
        assertEquals(partial.brief(), unrelated.brief());
    }

    @Test
    void 只有目标周和餐次时必须继续追问餐食目标() {
        MealPlanBriefService.UpdateResult partial = service.update(MealPlanBrief.empty(),
                "下周安排早餐、午餐和晚餐");

        assertFalse(partial.brief().isComplete());
        assertEquals(List.of("healthGoal"), partial.missingFields());
        assertTrue(partial.guidance().contains("餐食目标"));
        assertFalse(partial.brief().isConfirmedAndComplete());
    }
}
