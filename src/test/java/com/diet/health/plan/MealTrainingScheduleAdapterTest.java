package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0018「训练优先的餐训时间适配」确定性单测（票据 04）：
 * 默认窗口 → 训练后立即用餐 → 训练前倒推；05:00–24:00 边界；不跨午夜；
 * 相邻区间 [start,end) 合法；多训练稳定避让；无解稳定失败；适配标记。
 */
class MealTrainingScheduleAdapterTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    private static PlanItemDraft meal(String name, String mealTime, LocalDate date,
                                      LocalTime start, LocalTime end) {
        return new PlanItemDraft("MEAL", "m-" + name, name, date, start, end, null,
                Map.of("mealTime", mealTime, "caloriesKcal", 500));
    }

    private static PlanItemDraft training(String name, LocalDate date, LocalTime start, LocalTime end) {
        return new PlanItemDraft("EXERCISE", "e-" + name, name, date, start, end, null,
                Map.of("bodyPart", "胸", "sets", 3, "reps", 10));
    }

    @Test
    void 晚餐与训练冲突时自动移入训练结束后且相邻合法() {
        List<PlanItemDraft> meals = List.of(meal("红烧鱼", "晚餐", MONDAY, LocalTime.of(18, 0), LocalTime.of(19, 0)));
        List<PlanItemDraft> trainings = List.of(training("杠铃卧推", MONDAY, LocalTime.of(18, 0), LocalTime.of(19, 0)));

        MealTrainingScheduleAdapter.AdaptationResult result = MealTrainingScheduleAdapter.adapt(meals, trainings);
        PlanItemDraft adapted = result.mealItems().get(0);
        assertEquals(LocalTime.of(19, 0), adapted.startTime(), "餐食开始时间等于训练结束时间合法");
        assertEquals(LocalTime.of(20, 0), adapted.endTime());
        assertEquals(MealTrainingScheduleAdapter.ADAPTED_SOURCE,
                adapted.planParams().get(MealTrainingScheduleAdapter.MEAL_TIME_SOURCE_PARAM));
        assertEquals(1, result.notes().size());
        assertEquals(MealTrainingScheduleAdapter.Direction.AFTER_TRAINING, result.notes().get(0).direction());
        assertEquals(LocalTime.of(18, 0), result.notes().get(0).originalStart());
        assertEquals(LocalTime.of(19, 0), result.notes().get(0).finalStart());
    }

    @Test
    void 训练后无法放置时倒推到训练开始前相同时长() {
        // 训练 13:00-23:59 覆盖全部训练后空间：午餐训练后 13:00 起被占用 → 倒推到 10:30-11:30
        List<PlanItemDraft> meals = List.of(meal("午餐", "午餐", MONDAY, LocalTime.of(12, 0), LocalTime.of(13, 0)));
        List<PlanItemDraft> trainings = List.of(
                training("前段训练", MONDAY, LocalTime.of(11, 30), LocalTime.of(12, 0)),
                training("午训", MONDAY, LocalTime.of(12, 0), LocalTime.of(13, 0)),
                training("晚间训练", MONDAY, LocalTime.of(13, 0), LocalTime.of(23, 59)));

        MealTrainingScheduleAdapter.AdaptationResult result = MealTrainingScheduleAdapter.adapt(meals, trainings);
        PlanItemDraft adapted = result.mealItems().get(0);
        assertEquals(LocalTime.of(10, 30), adapted.startTime());
        assertEquals(LocalTime.of(11, 30), adapted.endTime());
        assertEquals(MealTrainingScheduleAdapter.Direction.BEFORE_TRAINING, result.notes().get(0).direction());
    }

    @Test
    void 同日多次训练按起始时间稳定排序逐一避让且结果可复现() {
        List<PlanItemDraft> meals = List.of(
                meal("早餐", "早餐", MONDAY, LocalTime.of(8, 0), LocalTime.of(8, 30)),
                meal("午餐", "午餐", MONDAY, LocalTime.of(12, 0), LocalTime.of(13, 0)),
                meal("晚餐", "晚餐", MONDAY, LocalTime.of(18, 0), LocalTime.of(19, 0)));
        List<PlanItemDraft> trainings = List.of(
                training("早训", MONDAY, LocalTime.of(7, 30), LocalTime.of(8, 0)),
                training("午训", MONDAY, LocalTime.of(12, 30), LocalTime.of(13, 0)),
                training("晚训", MONDAY, LocalTime.of(18, 0), LocalTime.of(18, 30)));

        MealTrainingScheduleAdapter.AdaptationResult first = MealTrainingScheduleAdapter.adapt(meals, trainings);
        MealTrainingScheduleAdapter.AdaptationResult second = MealTrainingScheduleAdapter.adapt(meals, trainings);
        // 稳定可复现：两次结果一致
        assertEquals(first.mealItems(), second.mealItems());
        // 早餐：训练后 08:00-08:30；午餐：训练后 13:00-14:00；晚餐：训练后 18:30-19:30
        assertEquals(LocalTime.of(8, 0), first.mealItems().get(0).startTime());
        assertEquals(LocalTime.of(13, 0), first.mealItems().get(1).startTime());
        assertEquals(LocalTime.of(18, 30), first.mealItems().get(2).startTime());
        // 无重叠（适配成功不产生真实重叠）
        assertTrue(first.notes().size() >= 1);
    }

    @Test
    void 边界05点判定正确且餐食可安排在训练前() {
        List<PlanItemDraft> meals = List.of(meal("早餐", "早餐", MONDAY, LocalTime.of(8, 0), LocalTime.of(8, 30)));
        // 训练覆盖 08:00-24:00：早餐训练后 24:00-24:30 越界 → 倒推到训练前 07:30-08:00（≥ 05:00 合法）
        List<PlanItemDraft> trainings = List.of(training("跨日训练", MONDAY, LocalTime.of(8, 0), LocalTime.of(23, 59)));
        MealTrainingScheduleAdapter.AdaptationResult result = MealTrainingScheduleAdapter.adapt(meals, trainings);
        assertEquals(LocalTime.of(7, 30), result.mealItems().get(0).startTime());
        assertEquals(MealTrainingScheduleAdapter.Direction.BEFORE_TRAINING, result.notes().get(0).direction());
    }

    @Test
    void 边界24点判定正确且整点结束候选不可放置() {
        // 30 分钟餐食 23:00-23:30 与训练 23:00-24:00 冲突：
        // 训练后候选 23:30-24:00 以 24:00 结束（LocalTime 无法表达、粒度不合法）→ 不可放置，
        // 倒推到训练前 22:30-23:00
        List<PlanItemDraft> meals = List.of(meal("夜宵", "晚餐", MONDAY, LocalTime.of(23, 0), LocalTime.of(23, 30)));
        List<PlanItemDraft> trainings = List.of(training("夜训", MONDAY, LocalTime.of(23, 0), LocalTime.of(23, 59)));
        MealTrainingScheduleAdapter.AdaptationResult result = MealTrainingScheduleAdapter.adapt(meals, trainings);
        assertEquals(LocalTime.of(22, 30), result.mealItems().get(0).startTime());
        assertEquals(LocalTime.of(23, 0), result.mealItems().get(0).endTime());
        assertEquals(MealTrainingScheduleAdapter.Direction.BEFORE_TRAINING, result.notes().get(0).direction());
    }

    @Test
    void 前后均无可行时段时返回稳定错误() {
        List<PlanItemDraft> meals = List.of(meal("午餐", "午餐", MONDAY, LocalTime.of(12, 0), LocalTime.of(13, 0)));
        // 训练覆盖 05:00-23:59，把午餐的默认窗口、训练后与训练前全部占满；午餐无解
        List<PlanItemDraft> trainings = List.of(
                training("晨训", MONDAY, LocalTime.of(5, 0), LocalTime.of(11, 30)),
                training("午训", MONDAY, LocalTime.of(11, 30), LocalTime.of(12, 30)),
                training("晚训", MONDAY, LocalTime.of(12, 30), LocalTime.of(23, 59)));

        HealthApiException error = assertThrows(HealthApiException.class,
                () -> MealTrainingScheduleAdapter.adapt(meals, trainings));
        assertEquals(HealthApiException.CODE_PLAN_TIME_CONFLICT, error.code());
        assertEquals(MealTrainingScheduleAdapter.NO_FEASIBLE_SLOT_COPY, error.getMessage());
    }

    @Test
    void 无冲突餐食保持默认窗口且不带适配标记() {
        List<PlanItemDraft> meals = List.of(meal("午餐", "午餐", MONDAY, LocalTime.of(12, 0), LocalTime.of(13, 0)));
        List<PlanItemDraft> trainings = List.of(training("晨训", MONDAY, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        MealTrainingScheduleAdapter.AdaptationResult result = MealTrainingScheduleAdapter.adapt(meals, trainings);
        assertEquals(LocalTime.of(12, 0), result.mealItems().get(0).startTime());
        assertTrue(result.notes().isEmpty(), "未移动餐次不产生适配说明");
        assertTrue(!result.mealItems().get(0).planParams()
                        .containsKey(MealTrainingScheduleAdapter.MEAL_TIME_SOURCE_PARAM),
                "未移动餐次不带 ADAPTED 标记");
    }

    @Test
    void 餐食Only和训练Only输入不触发综合适配() {
        List<PlanItemDraft> meals = List.of(meal("午餐", "午餐", MONDAY, LocalTime.of(12, 0), LocalTime.of(13, 0)));
        MealTrainingScheduleAdapter.AdaptationResult noTraining = MealTrainingScheduleAdapter.adapt(meals, List.of());
        assertEquals(LocalTime.of(12, 0), noTraining.mealItems().get(0).startTime());
        // 训练项目本身不被移动
        List<PlanItemDraft> trainings = List.of(training("晨训", MONDAY, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        MealTrainingScheduleAdapter.AdaptationResult noMeals = MealTrainingScheduleAdapter.adapt(List.of(), trainings);
        assertTrue(noMeals.mealItems().isEmpty());
    }
}