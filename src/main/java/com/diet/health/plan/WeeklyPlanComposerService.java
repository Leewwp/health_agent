package com.diet.health.plan;

import com.diet.health.intent.HealthInputNormalizer;
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
    private final HealthInputNormalizer normalizer;

    @org.springframework.beans.factory.annotation.Autowired
    public WeeklyPlanComposerService(HealthResourceProvider resourceProvider, MealPlanPicker mealPlanPicker,
                                     HealthInputNormalizer normalizer) {
        this.mealPlanPicker = mealPlanPicker;
        this.normalizer = normalizer;
    }

    /** 兼容旧测试构造：内部持有等价的纯词表归一器。 */
    public WeeklyPlanComposerService(HealthResourceProvider resourceProvider, MealPlanPicker mealPlanPicker) {
        this(resourceProvider, mealPlanPicker, new HealthInputNormalizer());
    }

    /** 按日预算生成餐食项目；结果只包含 MEAL。 */
    public List<PlanItemDraft> composeMeals(int calorieLow, int calorieHigh, LocalDate weekStart) {
        return composeMeals(calorieLow, calorieHigh, weekStart, List.of("早餐", "午餐", "晚餐"));
    }

    /** 只生成当前简报选择的餐次，并在一周内按使用次数优先更换餐食（无偏好，行为与历史一致）。 */
    public List<PlanItemDraft> composeMeals(int calorieLow, int calorieHigh, LocalDate weekStart,
                                            List<String> mealTimes) {
        return composeMealsWithPreferences(calorieLow, calorieHigh, weekStart, mealTimes, null).items();
    }

    /** 组合结果：计划项目 + 生成说明（未支持偏好与按日回退记录）。 */
    public record MealCompositionResult(List<PlanItemDraft> items, GenerationNotes generationNotes) {
    }

    /**
     * 消费简报可选偏好的组合入口：无偏好时挑选行为与 {@link #composeMeals} 完全一致；
     * 有偏好时先按餐次分桶做偏好过滤，某日偏好池为空或无法形成完整餐次组合时整天回退，
     * 并按“日期 + 字段:值”粒度记录未满足偏好，形成 generationNotes（三处可见合同的来源）。
     */
    public MealCompositionResult composeMealsWithPreferences(int calorieLow, int calorieHigh, LocalDate weekStart,
                                                             List<String> mealTimes, MealPlanBrief brief) {
        MealPreferenceFilter filter = MealPreferenceFilter.from(brief, normalizer);
        List<PlanItemDraft> items = new ArrayList<>();
        List<GenerationNotes.FallbackDay> fallbacks = new ArrayList<>();
        List<String> requestedSlots = mealTimes == null ? List.of() : mealTimes;
        Map<String, Integer> usage = new HashMap<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = weekStart.plusDays(offset);
            // 无偏好时走既有 pickForDay 接缝（行为与现状一致）；有偏好才进入偏好过滤挑选
            MealPlanPicker.PreferencePickResult result = filter.isEmpty()
                    ? new MealPlanPicker.PreferencePickResult(
                            mealPlanPicker.pickForDay(calorieLow, calorieHigh, requestedSlots, usage), false, List.of())
                    : mealPlanPicker.pickForDayWithPreferences(calorieLow, calorieHigh, requestedSlots, usage, filter);
            for (MealPlanPicker.MealPick pick : result.picks()) {
                items.add(mealItem(date, pick));
                usage.merge(pick.resourceId(), 1, Integer::sum);
            }
            if (result.fallback() && !result.picks().isEmpty()) {
                fallbacks.add(new GenerationNotes.FallbackDay(date.toString(), requestedSlots,
                        result.unmetPreferences()));
            }
        }
        GenerationNotes notes = brief == null ? GenerationNotes.empty()
                : new GenerationNotes(brief.unsupportedPreferences(), fallbacks);
        return new MealCompositionResult(List.copyOf(items), notes);
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
