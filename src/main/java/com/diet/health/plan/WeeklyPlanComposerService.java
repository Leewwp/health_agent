package com.diet.health.plan;

import com.diet.health.module.ExerciseModule;
import com.diet.health.module.HealthResource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 周计划确定性组合器（34 号，规格 8.2）：
 * 一周按本地周一至周日落位作息、三餐与训练；训练只使用 plan_ready 动作，
 * 安排在周一/三/五且主训练部位不连续；餐食按日能量预算挑选。组合结果必须经过
 * PlanValidationService 校验后才能持久化或激活。
 */
@Service
public class WeeklyPlanComposerService {

    /** 固定作息：每日睡眠（来源 R1 事实，23:00-07:00）。 */
    private static final String SLEEP_RESOURCE_ID = "R1";
    private static final String SLEEP_NAME = "睡眠";
    private static final LocalTime SLEEP_START = LocalTime.of(23, 0);
    private static final LocalTime SLEEP_END = LocalTime.of(7, 0);

    /** 三餐时段（早餐/午餐/晚餐）。 */
    private static final List<SlotWindow> MEAL_WINDOWS = List.of(
            new SlotWindow("早餐", LocalTime.of(8, 0), LocalTime.of(8, 30)),
            new SlotWindow("午餐", LocalTime.of(12, 0), LocalTime.of(13, 0)),
            new SlotWindow("晚餐", LocalTime.of(18, 0), LocalTime.of(19, 0))
    );

    /** 训练日：周一/三/五（一周内偏移 0/2/4）。 */
    private static final List<Integer> TRAINING_DAY_OFFSETS = List.of(0, 2, 4);
    private static final LocalTime TRAINING_START = LocalTime.of(19, 30);
    private static final LocalTime TRAINING_END = LocalTime.of(21, 0);

    /** 确定性训练剂量（Java 决定，LLM 不参与）。 */
    private static final int TRAINING_SETS = 3;
    private static final int TRAINING_REPS = 12;

    private final ExerciseModule exerciseModule;
    private final MealPlanPicker mealPlanPicker;

    public WeeklyPlanComposerService(ExerciseModule exerciseModule, MealPlanPicker mealPlanPicker) {
        this.exerciseModule = exerciseModule;
        this.mealPlanPicker = mealPlanPicker;
    }

    /** 组合一周计划：weekStart 为本地周一；trainingFocus 为可选主训练部位偏好。 */
    public List<PlanItemDraft> compose(int calorieLow, int calorieHigh, LocalDate weekStart,
                                       String timezone, String trainingFocus) {
        List<PlanItemDraft> items = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = weekStart.plusDays(offset);
            items.add(sleepItem(date));
            for (MealPlanPicker.MealPick pick : mealPlanPicker.pickForDay(calorieLow, calorieHigh)) {
                items.add(mealItem(date, pick));
            }
            if (TRAINING_DAY_OFFSETS.contains(offset)) {
                items.addAll(trainingItem(date, trainingFocus));
            }
        }
        return items;
    }

    private PlanItemDraft sleepItem(LocalDate date) {
        return new PlanItemDraft("ROUTINE", SLEEP_RESOURCE_ID, SLEEP_NAME, date, SLEEP_START, SLEEP_END, null, Map.of());
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

    /** 训练日挑选：按主训练部位轮转，保证连续训练日部位不重复。 */
    private List<PlanItemDraft> trainingItem(LocalDate date, String trainingFocus) {
        List<HealthResource> planReady = exerciseModule.listAll().stream()
                .filter(HealthResource::planReady)
                .toList();
        if (planReady.isEmpty()) {
            return List.of();
        }
        List<String> parts = orderedDistinctParts(planReady);
        if (parts.isEmpty()) {
            return List.of();
        }
        int trainingIndex = Math.max(0, TRAINING_DAY_OFFSETS.indexOf(date.getDayOfWeek().getValue() - 1));
        int startIndex = trainingFocus == null ? 0 : Math.max(0, parts.indexOf(trainingFocus));
        String part = parts.get((startIndex + trainingIndex) % parts.size());
        HealthResource exercise = planReady.stream()
                .filter(item -> item.tags().getOrDefault("primaryBodyPart", List.of()).contains(part))
                .findFirst()
                .orElse(planReady.get(0));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bodyPart", part);
        params.put("sets", TRAINING_SETS);
        params.put("reps", TRAINING_REPS);
        return List.of(new PlanItemDraft("EXERCISE", exercise.resourceId(), exercise.name(), date,
                TRAINING_START, TRAINING_END, null, params));
    }

    /** 主训练部位按出现顺序去重（保持确定性）。 */
    private List<String> orderedDistinctParts(List<HealthResource> planReady) {
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        for (HealthResource item : planReady) {
            for (String part : item.tags().getOrDefault("primaryBodyPart", List.of())) {
                seen.putIfAbsent(part, Boolean.TRUE);
            }
        }
        return List.copyOf(seen.keySet());
    }

    private record SlotWindow(String name, LocalTime start, LocalTime end) {
    }
}
