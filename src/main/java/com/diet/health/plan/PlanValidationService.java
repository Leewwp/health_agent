package com.diet.health.plan;

import com.diet.health.enums.HealthRiskLevel;
import com.diet.health.enums.PlanValidationLevel;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 组合时 Guard（34 号，规格 8.2/8.3/9）：
 * 确定性校验周计划不变量——未满 18 岁、65 岁以上训练计划、作息/训练时间冲突、
 * 同一训练部位连续两天、资源资格与引用、餐食日能量区间。
 * <p>
 * 结果分类：BLOCK_PLAN → HARD_ERROR（拒绝保存/激活）、ADVISORY → WARNING（可保存不可激活）、
 * 无命中 → OK。多规则取最高等级，规则命中带 ruleCode/ruleVersion/stage/severity 供 Trace。
 * 未经本服务校验的计划不得持久化或激活。
 */
@Service
public class PlanValidationService {

    /** 组合时规则集版本（与 32 号风险规则版本独立演进）。 */
    public static final String RULES_VERSION = "2026-08-10-plan-v1";

    /** 校验阶段名。 */
    public static final String STAGE_COMPOSE = "COMPOSE";

    /** 未满 18 岁文案（与 32 号风险规则共用固定文案）。 */
    public static final String UNDERAGE_COPY = com.diet.health.risk.HealthRiskRuleService.UNDERAGE_COPY;

    /** 65 岁以上训练计划文案（与 32 号风险规则共用固定文案）。 */
    public static final String SENIOR_TRAINING_COPY = com.diet.health.risk.HealthRiskRuleService.SENIOR_TRAINING_COPY;

    /** 时间冲突文案。 */
    public static final String SCHEDULE_OVERLAP_COPY = "作息与训练时间存在冲突，请调整后再保存。";

    /** 训练部位连续两天文案。 */
    public static final String BODY_PART_CONSECUTIVE_COPY = "同一主要训练部位不宜连续两天安排，请错开训练部位。";

    /** 资源资格文案。 */
    public static final String RESOURCE_NOT_PLAN_READY_COPY = "计划中引用了暂不具备自动计划资格的动作。";

    /** 资源不存在文案。 */
    public static final String RESOURCE_NOT_FOUND_COPY = "计划中引用的资源不存在或已下线。";

    /** 能量区间文案。 */
    public static final String ENERGY_OUT_OF_RANGE_COPY = "当日餐食估算热量超出推荐区间，激活前建议先调整。";

    /** 餐食时间窗口文案（规格 8.2：餐食校验时间窗口和日能量区间）。 */
    public static final String MEAL_TIME_WINDOW_COPY = "餐食安排时间与对应餐次不符，激活前建议先调整。";

    /** 餐次时间窗口（超出即提示调整；首版只覆盖早/午/晚三餐）。 */
    private static final Map<String, TimeWindow> MEAL_WINDOWS = Map.of(
            "早餐", new TimeWindow(LocalTime.of(5, 0), LocalTime.of(10, 0)),
            "午餐", new TimeWindow(LocalTime.of(10, 0), LocalTime.of(15, 0)),
            "晚餐", new TimeWindow(LocalTime.of(15, 0), LocalTime.of(22, 0))
    );

    /** 校验入参：档案上下文（只携带规则所需字段）。 */
    public record ProfileContext(int age, int calorieLow, int calorieHigh) {
    }

    /** 资源目录（组合器确认后的资格快照，供引用与资格校验）。 */
    public record ResourceCatalog(Set<String> planReadyExerciseIds, Set<String> knownExerciseIds, Set<String> knownRoutineFactIds) {
        public ResourceCatalog {
            planReadyExerciseIds = planReadyExerciseIds == null ? Set.of() : Set.copyOf(planReadyExerciseIds);
            knownExerciseIds = knownExerciseIds == null ? Set.of() : Set.copyOf(knownExerciseIds);
            knownRoutineFactIds = knownRoutineFactIds == null ? Set.of() : Set.copyOf(knownRoutineFactIds);
        }
    }

    /** 单条规则命中。 */
    public record RuleHit(String ruleCode, String ruleVersion, String stage, HealthRiskLevel severity,
                          PlanValidationLevel decision, String copy, String detail) {
    }

    /** 校验结果：level 为最高等级，copy 为该等级固定文案。 */
    public record ValidationResult(PlanValidationLevel level, List<RuleHit> hits, String copy) {
        public boolean blocked() {
            return level == PlanValidationLevel.HARD_ERROR;
        }

        public boolean activatable() {
            return level == PlanValidationLevel.OK;
        }
    }

    /** 校验计划项目集合。 */
    public ValidationResult validate(ProfileContext profile, List<PlanItemDraft> items, ResourceCatalog catalog) {
        List<RuleHit> hits = new ArrayList<>();
        List<PlanItemDraft> safeItems = items == null ? List.of() : items;

        hits.addAll(checkAge(profile, safeItems));
        hits.addAll(checkScheduleOverlap(safeItems));
        hits.addAll(checkBodyPartConsecutive(safeItems));
        hits.addAll(checkEnergyRange(profile, safeItems));
        hits.addAll(checkMealTimeWindow(safeItems));
        hits.addAll(checkResources(safeItems, catalog));

        return aggregate(hits);
    }

    /** 年龄维度：未满 18 岁 / 65 岁以上训练计划。 */
    private List<RuleHit> checkAge(ProfileContext profile, List<PlanItemDraft> items) {
        List<RuleHit> hits = new ArrayList<>();
        if (profile.age() < 18) {
            hits.add(hit("UNDERAGE", HealthRiskLevel.BLOCK_PLAN, UNDERAGE_COPY, "age=" + profile.age()));
        } else if (profile.age() >= 65 && hasExerciseItem(items)) {
            hits.add(hit("SENIOR_TRAINING", HealthRiskLevel.BLOCK_PLAN, SENIOR_TRAINING_COPY, "age=" + profile.age()));
        }
        return hits;
    }

    private boolean hasExerciseItem(List<PlanItemDraft> items) {
        return items != null && items.stream().anyMatch(PlanItemDraft::isExercise);
    }

    /** 作息与训练（含作息之间）同一天时间不得重叠；餐食不参与冲突校验（规格 8.2）。 */
    private List<RuleHit> checkScheduleOverlap(List<PlanItemDraft> items) {
        Map<LocalDate, List<PlanItemDraft>> byDay = new TreeMap<>();
        for (PlanItemDraft item : items) {
            if ((item.isRoutine() || item.isExercise()) && item.startTime() != null && item.endTime() != null) {
                byDay.computeIfAbsent(item.localDate(), key -> new ArrayList<>()).add(item);
            }
        }
        List<RuleHit> hits = new ArrayList<>();
        for (Map.Entry<LocalDate, List<PlanItemDraft>> entry : byDay.entrySet()) {
            List<Interval> intervals = new ArrayList<>();
            for (PlanItemDraft item : entry.getValue()) {
                int start = item.startTime().toSecondOfDay() / 60;
                int end = item.endTime().toSecondOfDay() / 60;
                if (end > start) {
                    intervals.add(new Interval(item, start, end));
                } else {
                    // 跨午夜区间拆成两段：[start, 24:00) 与 [00:00, end)
                    intervals.add(new Interval(item, start, 24 * 60));
                    intervals.add(new Interval(item, 0, end));
                }
            }
            for (int i = 0; i < intervals.size(); i++) {
                for (int j = i + 1; j < intervals.size(); j++) {
                    Interval a = intervals.get(i);
                    Interval b = intervals.get(j);
                    if (a.item() == b.item()) {
                        continue;
                    }
                    if (Math.max(a.start(), b.start()) < Math.min(a.end(), b.end())) {
                        hits.add(hit("SCHEDULE_OVERLAP", HealthRiskLevel.BLOCK_PLAN, SCHEDULE_OVERLAP_COPY,
                                "date=" + entry.getKey() + ", " + a.item().name() + " " + a.item().startTime() + "-"
                                        + a.item().endTime() + " 与 " + b.item().name() + " " + b.item().startTime() + "-"
                                        + b.item().endTime()));
                    }
                }
            }
        }
        return hits;
    }

    /** 分钟级时间区间（跨午夜已拆段）。 */
    private record Interval(PlanItemDraft item, int start, int end) {
    }

    /** 同一主训练部位不得安排连续两天。 */
    private List<RuleHit> checkBodyPartConsecutive(List<PlanItemDraft> items) {
        Map<String, List<LocalDate>> datesByPart = new HashMap<>();
        for (PlanItemDraft item : items) {
            if (item.isExercise() && item.bodyPart() != null) {
                datesByPart.computeIfAbsent(item.bodyPart(), key -> new ArrayList<>()).add(item.localDate());
            }
        }
        List<RuleHit> hits = new ArrayList<>();
        for (Map.Entry<String, List<LocalDate>> entry : datesByPart.entrySet()) {
            List<LocalDate> dates = entry.getValue().stream().sorted().toList();
            for (int i = 1; i < dates.size(); i++) {
                if (ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)) == 1) {
                    hits.add(hit("BODY_PART_CONSECUTIVE", HealthRiskLevel.BLOCK_PLAN, BODY_PART_CONSECUTIVE_COPY,
                            "bodyPart=" + entry.getKey() + ", dates=" + dates.get(i - 1) + "," + dates.get(i)));
                }
            }
        }
        return hits;
    }

    /** 餐食只校验日能量区间（每餐 caloriesKcal 之和 ∈ [low, high]），越界为 WARNING。 */
    private List<RuleHit> checkEnergyRange(ProfileContext profile, List<PlanItemDraft> items) {
        Map<LocalDate, Integer> sumByDay = new HashMap<>();
        for (PlanItemDraft item : items) {
            if (item.isMeal() && item.caloriesKcal() != null) {
                sumByDay.merge(item.localDate(), item.caloriesKcal(), Integer::sum);
            }
        }
        List<RuleHit> hits = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> entry : sumByDay.entrySet()) {
            int sum = entry.getValue();
            if (sum < profile.calorieLow() || sum > profile.calorieHigh()) {
                hits.add(hit("ENERGY_OUT_OF_RANGE", HealthRiskLevel.ADVISORY, ENERGY_OUT_OF_RANGE_COPY,
                        "date=" + entry.getKey() + ", sum=" + sum + " kcal, range=[" + profile.calorieLow() + ","
                                + profile.calorieHigh() + "]"));
            }
        }
        return hits;
    }

    /** 餐食时间窗口：早/午/晚餐落在对应餐次窗口内，越界为 WARNING（规格 8.2）。 */
    private List<RuleHit> checkMealTimeWindow(List<PlanItemDraft> items) {
        List<RuleHit> hits = new ArrayList<>();
        for (PlanItemDraft item : items) {
            if (!item.isMeal() || item.startTime() == null) {
                continue;
            }
            Object mealTime = item.planParams().get("mealTime");
            TimeWindow window = mealTime == null ? null : MEAL_WINDOWS.get(String.valueOf(mealTime));
            if (window != null && (item.startTime().isBefore(window.start()) || item.startTime().isAfter(window.end()))) {
                hits.add(hit("MEAL_TIME_OUT_OF_WINDOW", HealthRiskLevel.ADVISORY, MEAL_TIME_WINDOW_COPY,
                        "date=" + item.localDate() + ", mealTime=" + mealTime + ", start=" + item.startTime()));
            }
        }
        return hits;
    }

    private record TimeWindow(LocalTime start, LocalTime end) {
    }

    /** 资源引用与资格：动作必须存在且 plan_ready，作息事实必须存在。 */
    private List<RuleHit> checkResources(List<PlanItemDraft> items, ResourceCatalog catalog) {
        List<RuleHit> hits = new ArrayList<>();
        for (PlanItemDraft item : items) {
            if (item.isExercise()) {
                if (!catalog.knownExerciseIds().contains(item.resourceId())) {
                    hits.add(hit("RESOURCE_NOT_FOUND", HealthRiskLevel.BLOCK_PLAN, RESOURCE_NOT_FOUND_COPY,
                            "resourceId=" + item.resourceId()));
                } else if (!catalog.planReadyExerciseIds().contains(item.resourceId())) {
                    hits.add(hit("RESOURCE_NOT_PLAN_READY", HealthRiskLevel.BLOCK_PLAN, RESOURCE_NOT_PLAN_READY_COPY,
                            "resourceId=" + item.resourceId()));
                }
            } else if (item.isRoutine() && !catalog.knownRoutineFactIds().contains(item.resourceId())) {
                hits.add(hit("RESOURCE_NOT_FOUND", HealthRiskLevel.BLOCK_PLAN, RESOURCE_NOT_FOUND_COPY,
                        "resourceId=" + item.resourceId()));
            }
        }
        return hits;
    }

    private RuleHit hit(String ruleCode, HealthRiskLevel severity, String copy, String detail) {
        return new RuleHit(ruleCode, RULES_VERSION, STAGE_COMPOSE, severity,
                severity == HealthRiskLevel.BLOCK_PLAN ? PlanValidationLevel.HARD_ERROR : PlanValidationLevel.WARNING,
                copy, detail);
    }

    /** 聚合：多规则取最高等级；命中最高等级规则的第一条文案作为结果文案。 */
    private ValidationResult aggregate(List<RuleHit> hits) {
        if (hits.isEmpty()) {
            return new ValidationResult(PlanValidationLevel.OK, List.of(), null);
        }
        PlanValidationLevel highest = hits.stream()
                .map(RuleHit::decision)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(PlanValidationLevel.OK);
        String copy = hits.stream()
                .filter(hit -> hit.decision() == highest)
                .map(RuleHit::copy)
                .findFirst()
                .orElse(null);
        return new ValidationResult(highest, List.copyOf(hits), copy);
    }
}
