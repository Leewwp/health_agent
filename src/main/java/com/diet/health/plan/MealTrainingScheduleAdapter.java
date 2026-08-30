package com.diet.health.plan;

import com.diet.exception.HealthApiException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 训练优先的餐训时间适配（ADR-0018「训练优先的餐训时间适配」）：
 * 确定性、无 I/O、可单测的接缝——综合计划生成中，训练时间是用户指定的硬约束，
 * 餐食由本适配器在训练硬约束之后安排。候选时段顺序固定：
 * 先餐次默认窗口（早餐 08:00/30 分钟、午餐 12:00/60 分钟、晚餐 18:00/60 分钟），
 * 冲突时训练结束后立即开始用餐（允许餐食开始时间等于训练结束时间、不额外加间隔），
 * 再训练开始前倒推同等餐食时长；同日多次训练按训练起始时间稳定排序逐一避让；
 * 可行边界为本地 05:00–24:00，餐食不得跨午夜，相邻区间按 {@code [startTime,endTime)}
 * 视为合法；前后均无可行时段返回稳定错误，由调用方整体放弃综合计划（不生成半成品）。
 * 适配成功的餐食携带 {@code mealTimeSource=ADAPTED} 结构化标记，供最终 Guard 区分
 * “自动适配的窗口外餐食”与“用户/编辑器直接产生的窗口外餐食”。
 */
public final class MealTrainingScheduleAdapter {

    /** 计划参数键：餐食时间来源（ADAPTED = 训练优先自动适配结果）。 */
    public static final String MEAL_TIME_SOURCE_PARAM = "mealTimeSource";

    /** 自动适配的餐食时间来源值。 */
    public static final String ADAPTED_SOURCE = "ADAPTED";

    /** 当日可行调度边界（分钟）：本地 05:00–24:00。 */
    public static final int DAY_START_MINUTES = 5 * 60;
    public static final int DAY_END_MINUTES = 24 * 60;

    /** 无解时的稳定失败文案（综合计划整体失败并回滚）。 */
    public static final String NO_FEASIBLE_SLOT_COPY =
            "餐食无法安排在当天的训练时间之外（05:00–24:00），请调整训练时段或餐次后重试";

    /** 适配方向：训练后立即用餐 / 训练开始前倒推。 */
    public enum Direction {
        AFTER_TRAINING("训练后"),
        BEFORE_TRAINING("训练前");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 单条餐食适配记录（原始默认窗口、最终窗口与方向；训练前/后为 null 表示未移动）。 */
    public record AdaptationNote(LocalDate date, String mealTime,
                                 LocalTime originalStart, LocalTime originalEnd,
                                 LocalTime finalStart, LocalTime finalEnd,
                                 Direction direction) {
        public Map<String, Object> toMetadata() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", date.toString());
            entry.put("mealTime", mealTime);
            entry.put("originalStart", originalStart.toString());
            entry.put("originalEnd", originalEnd.toString());
            entry.put("finalStart", finalStart.toString());
            entry.put("finalEnd", finalEnd.toString());
            entry.put("direction", direction == null ? "KEEP" : direction.name());
            return entry;
        }
    }

    /** 适配结果：重排后的餐食项目（也含未移动项）+ 被移动餐次的说明列表。 */
    public record AdaptationResult(List<PlanItemDraft> mealItems, List<AdaptationNote> notes) {
        public AdaptationResult {
            mealItems = mealItems == null ? List.of() : List.copyOf(mealItems);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    private MealTrainingScheduleAdapter() {
    }

    /** 按训练硬约束安排餐食；无可行时段抛稳定 {@link HealthApiException}。 */
    public static AdaptationResult adapt(List<PlanItemDraft> mealItems, List<PlanItemDraft> trainingItems) {
        if (mealItems == null || mealItems.isEmpty()) {
            return new AdaptationResult(List.of(), List.of());
        }
        List<PlanItemDraft> trainings = trainingItems == null ? List.of() : trainingItems.stream()
                .filter(PlanItemDraft::isExercise)
                .toList();
        Map<LocalDate, List<Interval>> trainingsByDay = new LinkedHashMap<>();
        trainings.stream()
                .filter(item -> item.localDate() != null && item.startTime() != null && item.endTime() != null)
                .sorted(Comparator.comparing(PlanItemDraft::localDate)
                        .thenComparing(item -> toMinutes(item.startTime()))
                        .thenComparing(PlanItemDraft::resourceId))
                .forEach(item -> trainingsByDay.computeIfAbsent(item.localDate(), key -> new ArrayList<>())
                        .add(new Interval(item, toMinutes(item.startTime()), toMinutes(item.endTime()))));

        List<PlanItemDraft> adapted = new ArrayList<>();
        List<AdaptationNote> notes = new ArrayList<>();
        Map<LocalDate, List<Interval>> placedMealsByDay = new LinkedHashMap<>();
        // 处理顺序与输入一致（组合器按日、按餐次稳定生成），同日餐食按默认窗口先后逐个避让；
        // 已放置餐食与全部训练区间都是候选的占用基准，保证结果可重复。
        for (PlanItemDraft meal : mealItems) {
            if (!meal.isMeal() || meal.localDate() == null || meal.startTime() == null || meal.endTime() == null) {
                adapted.add(meal);
                continue;
            }
            LocalDate date = meal.localDate();
            List<Interval> dayTrainings = trainingsByDay.getOrDefault(date, List.of());
            List<Interval> placedMeals = new ArrayList<>(placedMealsByDay.getOrDefault(date, List.of()));
            int start = toMinutes(meal.startTime());
            int end = toMinutes(meal.endTime());
            Direction direction = null;
            int finalStart = start;
            int finalEnd = end;
            if (noOverlap(new Interval(null, start, end), dayTrainings) && noOverlap(new Interval(null, start, end), placedMeals)) {
                // 默认窗口可直接放置：不移动
                direction = null;
            } else {
                // 训练结束后立即开始用餐（候选按训练起始时间稳定排序，取第一个可行者）
                for (Interval training : dayTrainings) {
                    int candidateStart = training.end();
                    int candidateEnd = training.end() + (end - start);
                    if (withinDay(candidateStart, candidateEnd)
                            && noOverlap(new Interval(null, candidateStart, candidateEnd), dayTrainings)
                            && noOverlap(new Interval(null, candidateStart, candidateEnd), placedMeals)) {
                        finalStart = candidateStart;
                        finalEnd = candidateEnd;
                        direction = Direction.AFTER_TRAINING;
                        break;
                    }
                }
                if (direction == null) {
                    // 训练开始前倒推同等餐食时长
                    for (Interval training : dayTrainings) {
                        int candidateEnd = training.start();
                        int candidateStart = training.start() - (end - start);
                        if (withinDay(candidateStart, candidateEnd)
                                && noOverlap(new Interval(null, candidateStart, candidateEnd), dayTrainings)
                                && noOverlap(new Interval(null, candidateStart, candidateEnd), placedMeals)) {
                            finalStart = candidateStart;
                            finalEnd = candidateEnd;
                            direction = Direction.BEFORE_TRAINING;
                            break;
                        }
                    }
                }
                if (direction == null) {
                    throw new HealthApiException(HealthApiException.CODE_PLAN_TIME_CONFLICT, NO_FEASIBLE_SLOT_COPY);
                }
            }
            PlanItemDraft placed = moveMeal(meal, finalStart, finalEnd, direction != null);
            adapted.add(placed);
            placedMeals.add(new Interval(placed, finalStart, finalEnd));
            placedMealsByDay.put(date, placedMeals);
            if (direction != null) {
                notes.add(new AdaptationNote(date, String.valueOf(meal.planParams().getOrDefault("mealTime", "餐食")),
                        meal.startTime(), meal.endTime(),
                        toLocalTime(finalStart), toLocalTime(finalEnd), direction));
            }
        }
        return new AdaptationResult(List.copyOf(adapted), notes);
    }

    /** 移动餐食到指定区间：带 mealTimeSource=ADAPTED 结构化标记（成功适配不触发误导窗口告警）。 */
    private static PlanItemDraft moveMeal(PlanItemDraft meal, int startMinutes, int endMinutes, boolean adapted) {
        Map<String, Object> params = new LinkedHashMap<>(meal.planParams());
        if (adapted) {
            params.put(MEAL_TIME_SOURCE_PARAM, ADAPTED_SOURCE);
        }
        return new PlanItemDraft(meal.resourceType(), meal.resourceId(), meal.name(), meal.localDate(),
                toLocalTime(startMinutes), toLocalTime(endMinutes), meal.note(), params);
    }

    /**
 * 当日边界检查：start ≥ 05:00 且 end ≤ 24:00（不跨午夜）。
 * 结束时间恰好为 24:00 的候选视为不可放置：计划项目时间用 LocalTime 表达
 * （最大值 23:59:59.999…），且最终 Guard 要求整点/半点粒度，24:00 整点结束
 * 无法落库为合法时间；“24:00”在规则里只作为概念性上边界。
 */
    private static boolean withinDay(int startMinutes, int endMinutes) {
        return startMinutes >= DAY_START_MINUTES && endMinutes > startMinutes && endMinutes < DAY_END_MINUTES;
    }

    /** [start,end) 与给定区间集合是否无重叠。 */
    private static boolean noOverlap(Interval candidate, List<Interval> others) {
        for (Interval other : others) {
            if (Math.max(candidate.start(), other.start()) < Math.min(candidate.end(), other.end())) {
                return false;
            }
        }
        return true;
    }

    private static int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static LocalTime toLocalTime(int minutes) {
        return LocalTime.of(minutes / 60, minutes % 60);
    }

    /** 分钟级区间（item 可为 null 的候选区间，仅用于重叠判定）。 */
    private record Interval(PlanItemDraft item, int start, int end) {
    }
}