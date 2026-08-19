package com.diet.health.plan;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 餐食计划简报的独立解析、确认和字段指引服务。 */
@Service
public class MealPlanBriefService {

    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");

    public UpdateResult update(MealPlanBrief current, String input) {
        MealPlanBrief base = current == null ? MealPlanBrief.empty() : current;
        String text = input == null ? "" : input.trim();
        if (isConfirmation(text)) {
            if (base.isComplete()) {
                return new UpdateResult(base.confirm(), true, BriefInterpretationStatus.EXTRACTED,
                        missing(base), "", false);
            }
            return new UpdateResult(base, false, BriefInterpretationStatus.PARTIAL,
                    missing(base), guidance(firstMissing(base)), false);
        }
        if (isUnrelated(text)) {
            return new UpdateResult(base, false, BriefInterpretationStatus.UNRELATED,
                    missing(base), "已保留当前未完成的餐食简报，先处理你刚才的新话题。", false);
        }

        LocalDate weekStart = parseWeekStart(text);
        List<String> mealTimes = parseMealTimes(text);
        String healthGoal = parseGoal(text);
        if (weekStart == null && mealTimes.isEmpty() && healthGoal == null) {
            return new UpdateResult(base, false, BriefInterpretationStatus.INVALID,
                    missing(base), guidance(firstMissing(base)), looksLikeMealInput(text));
        }
        MealPlanBrief merged = base.withValues(weekStart, mealTimes, healthGoal);
        return new UpdateResult(merged, false, BriefInterpretationStatus.EXTRACTED,
                missing(merged), guidance(firstMissing(merged)), true);
    }

    public List<String> missing(MealPlanBrief brief) {
        MealPlanBrief value = brief == null ? MealPlanBrief.empty() : brief;
        List<String> missing = new ArrayList<>();
        if (value.weekStart() == null) missing.add("weekStart");
        if (value.mealTimes().isEmpty()) missing.add("mealTimes");
        return List.copyOf(missing);
    }

    public String summary(MealPlanBrief brief) {
        MealPlanBrief value = brief == null ? MealPlanBrief.empty() : brief;
        return "目标周：" + (value.weekStart() == null ? "未定" : value.weekStart())
                + "；餐次：" + (value.mealTimes().isEmpty() ? "未定" : String.join("、", value.mealTimes()))
                + "；目标：" + (value.healthGoal() == null ? "未定" : value.healthGoal());
    }

    public boolean looksLikeMealInput(String input) {
        String text = input == null ? "" : input;
        return !parseMealTimes(text).isEmpty()
                || text.contains("餐食") || text.contains("饮食") || text.contains("吃什么")
                || text.contains("减脂") || text.contains("增肌");
    }

    private boolean isConfirmation(String text) {
        return text.contains("确认") && (text.contains("餐食") || text.contains("饮食")
                || text.contains("简报") || text.contains("计划"));
    }

    private boolean isUnrelated(String text) {
        if (text.isBlank()) return false;
        return !looksLikeMealInput(text) && !text.contains("下周") && !text.contains("本周")
                && !text.contains("这周") && !ISO_DATE.matcher(text).find();
    }

    private List<String> parseMealTimes(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (text.contains("三餐") || text.contains("一日三餐")) {
            result.addAll(List.of("早餐", "午餐", "晚餐"));
        }
        if (text.contains("早餐") || text.contains("早饭")) result.add("早餐");
        if (text.contains("午餐") || text.contains("午饭") || text.contains("中饭")) result.add("午餐");
        if (text.contains("晚餐") || text.contains("晚饭")) result.add("晚餐");
        return List.copyOf(result);
    }

    private String parseGoal(String text) {
        if (text.contains("减脂") || text.contains("减重")) return "减脂";
        if (text.contains("增肌")) return "增肌";
        if (text.contains("维持") || text.contains("保持健康")) return "维持健康";
        return null;
    }

    private LocalDate parseWeekStart(String text) {
        Matcher matcher = ISO_DATE.matcher(text);
        while (matcher.find()) {
            try {
                LocalDate date = LocalDate.parse(matcher.group(1));
                return date.getDayOfWeek() == DayOfWeek.MONDAY ? date : null;
            } catch (Exception ignored) {
                return null;
            }
        }
        LocalDate today = LocalDate.now();
        if (text.contains("下周")) return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        if (text.contains("本周") || text.contains("这周")) {
            return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }
        return null;
    }

    private String firstMissing(MealPlanBrief brief) {
        return missing(brief).stream().findFirst().orElse(null);
    }

    private String guidance(String field) {
        return switch (field == null ? "" : field) {
            case "weekStart" -> "请补充目标周，例如“下周”或“目标周 2026-08-24”。";
            case "mealTimes" -> "请补充餐次，例如“早餐、午餐和晚餐”或“每天三餐”。";
            default -> "请补充餐食计划信息，例如“目标周下周，安排早餐、午餐和晚餐”。";
        };
    }

    public record UpdateResult(MealPlanBrief brief, boolean confirmedNow, BriefInterpretationStatus status,
                               List<String> missingFields, String guidance, boolean agentEligible) {
        public UpdateResult {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }
    }
}
