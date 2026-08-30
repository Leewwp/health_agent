package com.diet.health.plan;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DebugProbe2 {
    @Test
    void probe() {
        MealPlanBriefService service = new MealPlanBriefService();
        MealPlanBrief base = service.update(MealPlanBrief.empty(), "下周安排早餐、午餐和晚餐，想减脂").brief();
        MealPlanBriefService.UpdateResult r = service.update(base, "下周安排");
        System.out.println("STATUS=" + r.status());
        System.out.println("GUIDANCE=[" + r.guidance() + "]");
        System.out.println("WEEKSTART=" + r.brief().weekStart());
        assertTrue(r.guidance().contains("不需要指定日期"));
    }
}
