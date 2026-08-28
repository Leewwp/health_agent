package com.diet.health.plan;

import com.diet.health.resource.HealthResourceProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** 餐食子计划的确定性组合器，只生成 MEAL 项目，不附带训练或作息。 */
@Service
public class WeeklyPlanComposerService {

    private static final List<SlotWindow> MEAL_WINDOWS = List.of(
            new SlotWindow("早餐", LocalTime.of(8, 0), LocalTime.of(8, 30)),
            new SlotWindow("午餐", LocalTime.of(12, 0), LocalTime.of(13, 0)),
            new SlotWindow("晚餐", LocalTime.of(18, 0), LocalTime.of(19, 0))
    );

    private final MealPlanPicker mealPlanPicker;

    public WeeklyPlanComposerService(HealthResourceProvider resourceProvider, MealPlanPicker mealPlanPicker) {
        this.mealPlanPicker = mealPlanPicker;
    }

    /** 按日预算生成餐食项目；结果只包含 MEAL。 */
    public List<PlanItemDraft> composeMeals(int calorieLow, int calorieHigh, LocalDate weekStart) {
        return composeMeals(calorieLow, calorieHigh, weekStart, List.of("早餐", "午餐", "晚餐"));
    }

    /** 只生成已确认简报中的餐次，并在一周内按使用次数优先更换餐食。 */
    public List<PlanItemDraft> composeMeals(int calorieLow, int calorieHigh, LocalDate weekStart,
                                            List<String> mealTimes) {
        List<PlanItemDraft> items = new ArrayList<>();
        Map<String, Integer> usage = new HashMap<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = weekStart.plusDays(offset);
            for (MealPlanPicker.MealPick pick : mealPlanPicker.pickForDay(calorieLow, calorieHigh, mealTimes, usage)) {
                items.add(mealItem(date, pick));
                usage.merge(pick.resourceId(), 1, Integer::sum);
            }
        }
        return items;
    }

    private PlanItemDraft mealItem(LocalDate date, MealPlanPicker.MealPick pick) {
        SlotWindow window = MEAL_WINDOWS.stream()
                .filter(slot -> slot.name().equals(pick.mealTime()))
                .findFirst()
                .orElse(MEAL_WINDOWS.get(1));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mealTime", pick.mealTime());
        params.put("caloriesKcal", pick.caloriesKcal());
        return new PlanItemDraft("MEAL", pick.resourceId(), pick.name(), date,
                window.start(), window.end(), null, params);
    }

    private record SlotWindow(String name, LocalTime start, LocalTime end) {
    }
}
