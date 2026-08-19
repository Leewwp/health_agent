package com.diet.health.plan;

import com.diet.health.intent.HealthInputNormalizer;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 训练简报的确定性解析、合并、澄清和确认规则。 */
@Service
public class PlanBriefService {

    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern TIME_RANGE = Pattern.compile("([01]?\\d|2[0-3])[:：]([0-5]\\d)\\s*[-到至~～]\s*([01]?\\d|2[0-3])[:：]([0-5]\\d)");
    private static final Pattern TIME_SINGLE = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Map<String, DayOfWeek> DAY_NAMES = dayNames();

    private final HealthInputNormalizer normalizer;

    private static Map<String, DayOfWeek> dayNames() {
        Map<String, DayOfWeek> names = new LinkedHashMap<>();
        names.put("周一", DayOfWeek.MONDAY); names.put("星期一", DayOfWeek.MONDAY);
        names.put("周二", DayOfWeek.TUESDAY); names.put("星期二", DayOfWeek.TUESDAY);
        names.put("周三", DayOfWeek.WEDNESDAY); names.put("星期三", DayOfWeek.WEDNESDAY);
        names.put("周四", DayOfWeek.THURSDAY); names.put("星期四", DayOfWeek.THURSDAY);
        names.put("周五", DayOfWeek.FRIDAY); names.put("星期五", DayOfWeek.FRIDAY);
        names.put("周六", DayOfWeek.SATURDAY); names.put("星期六", DayOfWeek.SATURDAY);
        names.put("周日", DayOfWeek.SUNDAY); names.put("星期日", DayOfWeek.SUNDAY);
        return Map.copyOf(names);
    }

    public PlanBriefService(HealthInputNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /** 只解析当前 PLAN 轮次；普通推荐调用方不应调用本服务。 */
    public UpdateResult update(PlanBrief current, String input) {
        PlanBrief base = current == null ? PlanBrief.empty() : current;
        String text = input == null ? "" : input.trim();
        if (isConfirmation(text)) {
            if (base.isComplete()) {
                return new UpdateResult(base.confirm(), true, List.of());
            }
            return new UpdateResult(base, false, missing(base));
        }

        Map<String, List<String>> slots = normalizer.normalize(
                com.diet.health.enums.HealthDomain.EXERCISE, text, Map.of()).slots();
        String goal = first(slots.get("trainingGoal"));
        String difficulty = first(slots.get("difficulty"));
        List<String> bodyParts = slots.getOrDefault("bodyParts", List.of());
        List<String> equipment = slots.getOrDefault("equipment", List.of());
        LocalDate weekStart = parseWeekStart(text, LocalDate.now());
        List<DayOfWeek> days = parseDays(text);
        TrainingTimeWindow window = parseWindow(text);
        Map<String, List<String>> hardConstraints = parseHardConstraints(text);

        PlanBrief merged = new PlanBrief(
                goal == null ? base.trainingGoal() : goal,
                bodyParts.isEmpty() ? base.bodyParts() : bodyParts,
                equipment.isEmpty() ? base.equipment() : equipment,
                difficulty == null ? base.difficulty() : difficulty,
                weekStart == null ? base.weekStart() : weekStart,
                days.isEmpty() ? base.trainingDays() : days,
                window == null ? base.timeWindow() : window,
                hardConstraints.isEmpty() ? base.hardConstraints() : hardConstraints,
                false, base.confirmationVersion(), null);
        return new UpdateResult(merged, false, missing(merged));
    }

    public List<String> missing(PlanBrief brief) {
        if (brief == null) {
            return List.of("trainingGoal", "bodyParts", "equipment", "difficulty", "weekStart", "trainingDays", "timeWindow");
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(brief.trainingGoal())) missing.add("trainingGoal");
        if (brief.bodyParts().isEmpty()) missing.add("bodyParts");
        if (brief.equipment().isEmpty()) missing.add("equipment");
        if (isBlank(brief.difficulty())) missing.add("difficulty");
        if (brief.weekStart() == null) missing.add("weekStart");
        if (brief.trainingDays().isEmpty()) missing.add("trainingDays");
        if (brief.timeWindow() == null) missing.add("timeWindow");
        return List.copyOf(missing);
    }

    public String question(List<String> missing) {
        if (missing == null || missing.isEmpty()) return "请确认上面的训练偏好。";
        return switch (missing.get(0)) {
            case "trainingGoal" -> "这周训练主要想达成什么目标，例如增肌、减脂、耐力或保持健康？";
            case "bodyParts" -> "想重点安排哪些训练部位，例如胸、背、腿、核心或全身？";
            case "equipment" -> "你能使用哪些器械？可以说徒手、哑铃、弹力带或器械。";
            case "difficulty" -> "训练难度希望是入门、进阶还是挑战？";
            case "weekStart" -> "目标周从哪一天开始？请给我周一日期，例如 2026-08-24。";
            case "trainingDays" -> "一周想安排哪几天训练，例如周一、周三、周五？";
            case "timeWindow" -> "每次训练可安排在哪个时间段，例如 19:00-20:00？";
            default -> "还需要补充一点训练安排信息。";
        };
    }

    public String summary(PlanBrief brief) {
        String days = brief.trainingDays().stream().map(this::dayLabel).reduce((a, b) -> a + "、" + b).orElse("未定");
        String window = brief.timeWindow() == null ? "未定" : brief.timeWindow().start() + "-" + brief.timeWindow().end();
        return "训练目标：" + value(brief.trainingGoal()) + "；部位：" + join(brief.bodyParts())
                + "；器械：" + join(brief.equipment()) + "；难度：" + value(brief.difficulty())
                + "；目标周：" + value(brief.weekStart()) + "；训练日：" + days + "；时间：" + window;
    }

    private LocalDate parseWeekStart(String text, LocalDate today) {
        Matcher matcher = ISO_DATE.matcher(text);
        while (matcher.find()) {
            try {
                LocalDate date = LocalDate.parse(matcher.group(1), DATE_FORMAT);
                if (date.getDayOfWeek() == DayOfWeek.MONDAY) return date;
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        if (text.contains("下周")) return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        if (text.contains("本周") || text.contains("这周")) return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return null;
    }

    private List<DayOfWeek> parseDays(String text) {
        LinkedHashSet<DayOfWeek> days = new LinkedHashSet<>();
        DAY_NAMES.keySet().stream().sorted((left, right) -> Integer.compare(
                        DAY_NAMES.get(left).getValue(), DAY_NAMES.get(right).getValue()))
                .forEach(label -> {
                    if (text.contains(label)) days.add(DAY_NAMES.get(label));
                });
        return List.copyOf(days);
    }

    private TrainingTimeWindow parseWindow(String text) {
        Matcher range = TIME_RANGE.matcher(text);
        if (!range.find()) return null;
        try {
            LocalTime start = LocalTime.of(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)));
            LocalTime end = LocalTime.of(Integer.parseInt(range.group(3)), Integer.parseInt(range.group(4)));
            return start.isBefore(end) ? new TrainingTimeWindow(start, end) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, List<String>> parseHardConstraints(String text) {
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        if (text.contains("不要练胸") || text.contains("不练胸")) constraints.put("excludeBodyParts", List.of("胸"));
        if (text.contains("不要练腿") || text.contains("不练腿")) constraints.put("excludeBodyParts", List.of("腿"));
        if (text.contains("不要用哑铃") || text.contains("不用哑铃")) constraints.put("excludeEquipment", List.of("哑铃"));
        if (text.contains("不要用杠铃") || text.contains("不用杠铃")) constraints.put("excludeEquipment", List.of("杠铃"));
        return constraints;
    }

    private boolean isConfirmation(String text) {
        return text.contains("确认训练偏好") || text.equals("确认") || text.contains("确认简报") || text.contains("按这个生成");
    }

    private String first(List<String> values) { return values == null || values.isEmpty() ? null : values.get(0); }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    private String value(Object value) { return value == null ? "未定" : String.valueOf(value); }
    private String join(List<String> values) { return values == null || values.isEmpty() ? "未定" : String.join("、", values); }
    private String dayLabel(DayOfWeek day) { return "周" + "一二三四五六日".charAt(day.getValue() - 1); }

    public record UpdateResult(PlanBrief brief, boolean confirmedNow, List<String> missingFields) {
    }
}
