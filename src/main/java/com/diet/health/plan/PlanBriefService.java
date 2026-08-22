package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.intent.HealthInputNormalizer;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 训练简报的字段感知解析、合并、澄清和确认规则。 */
@Service
public class PlanBriefService {

    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern NUMERIC_TIME_RANGE = Pattern.compile(
            "([01]?\\d|2[0-3])[:：]([0-5]\\d)\\s*[-到至~～—]+\\s*([01]?\\d|2[0-3])[:：]([0-5]\\d)");
    private static final String TIME_POINT = "(?:(?:上午|早上|中午|下午|晚上|夜里)\\s*)?(?:[01]?\\d|2[0-3]|[零〇一二三四五六七八九十两]{1,3})\\s*(?:点|时)(?:半|一刻|三刻)?";
    private static final String BARE_TIME_POINT = "(?:(?:上午|早上|中午|下午|晚上|夜里)\\s*)?(?:[01]?\\d|2[0-3]|[零〇一二三四五六七八九十两]{1,3})";
    private static final Pattern HAN_TIME_RANGE = Pattern.compile("(" + TIME_POINT + ")\\s*(?:到|至|[-—])\\s*(" + TIME_POINT + ")");
    private static final Pattern HAN_TIME_RANGE_BARE_START = Pattern.compile("(" + BARE_TIME_POINT + ")\\s*(?:到|至|[-—])\\s*(" + TIME_POINT + ")");
    private static final Pattern HAN_TIME_POINT = Pattern.compile(
            "(?:(上午|早上|中午|下午|晚上|夜里)\\s*)?([01]?\\d|2[0-3]|[零〇一二三四五六七八九十两]{1,3})\\s*(?:点|时)(半|一刻|三刻)?");
    private static final Pattern HAN_BARE_TIME_POINT = Pattern.compile(
            "(?:(上午|早上|中午|下午|晚上|夜里)\\s*)?([01]?\\d|2[0-3]|[零〇一二三四五六七八九十两]{1,3})");
    private static final Pattern DECLARED_DAY_COUNT = Pattern.compile("(?:([1-7])|([一二三四五六七]))天");
    private static final Pattern DAY_RANGE = Pattern.compile("(?:周|星期)([一二三四五六日天])\\s*(?:到|至|-)\\s*(?:周|星期)?([一二三四五六日天])");
    private static final Pattern PREFIXED_DAYS = Pattern.compile("(?:周|星期)([一二三四五六日天]{2,7})");
    private static final Pattern COMPACT_DAYS = Pattern.compile("(?<![一二三四五六日天])([一二三四五六日天]{2,7})(?![一二三四五六日天])");
    private static final Pattern EXCLUDED_EXERCISE = Pattern.compile("(?:不要做|不做|排除动作[：:]?)([\\p{IsHan}A-Za-z0-9（）()·-]{2,30})");
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
        names.put("周天", DayOfWeek.SUNDAY); names.put("星期天", DayOfWeek.SUNDAY);
        return Map.copyOf(names);
    }

    public PlanBriefService(HealthInputNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /** 只解释当前 PLAN 轮次，不对简报做持久化意义上的写入。 */
    public PlanBriefInterpretation interpret(PlanBrief current, String input) {
        PlanBrief base = current == null ? PlanBrief.empty() : current;
        String text = input == null ? "" : input.trim();
        if (text.isBlank()) {
            return invalid(base, "请直接补充当前字段，例如“周二、周四”和“下午六点到七点”。", false);
        }
        if (isUnrelated(text)) {
            return new PlanBriefInterpretation(BriefInterpretationStatus.UNRELATED, base, Map.of(), text,
                    "已保留当前未完成简报，先处理你刚才的新话题。", false);
        }

        Map<String, List<String>> slots = normalizer.normalize(
                com.diet.health.enums.HealthDomain.EXERCISE, text, Map.of()).slots();
        String goal = first(slots.get("trainingGoal"));
        String difficulty = first(slots.get("difficulty"));
        List<String> bodyParts = slots.getOrDefault("bodyParts", List.of());
        List<String> equipment = slots.getOrDefault("equipment", List.of());
        LocalDate weekStart = parseWeekStart(text, LocalDate.now());
        DayParse days = parseDays(text);
        TimeParse time = parseWindow(text, base);
        Map<String, List<String>> hardConstraints = parseHardConstraints(text);
        rejectUnsupportedHardConstraints(text);

        if (days.ambiguous() || time.ambiguous()) {
            String field = days.ambiguous() ? "trainingDays" : "timeWindow";
            return new PlanBriefInterpretation(BriefInterpretationStatus.AMBIGUOUS, base, Map.of(), text,
                    guidance(field, BriefInterpretationStatus.AMBIGUOUS), true);
        }

        Map<String, List<String>> candidateFields = new LinkedHashMap<>();
        put(candidateFields, "trainingGoal", goal);
        put(candidateFields, "bodyParts", bodyParts);
        put(candidateFields, "equipment", equipment);
        put(candidateFields, "difficulty", difficulty);
        if (weekStart != null) put(candidateFields, "weekStart", weekStart.toString());
        if (!days.days().isEmpty()) put(candidateFields, "trainingDays", days.days().stream().map(this::dayLabel).toList());
        if (time.start() != null) put(candidateFields, "timeStart", time.start().toString());
        if (time.end() != null) put(candidateFields, "timeEnd", time.end().toString());
        hardConstraints.forEach(candidateFields::put);

        if (candidateFields.isEmpty()) {
            return invalid(base, guidance(base.expectedField(), BriefInterpretationStatus.INVALID),
                    likelyCurrentField(text, base));
        }
        PlanBrief parsed = merge(base, goal, bodyParts, equipment, difficulty, weekStart, days.days(), time, hardConstraints);
        BriefInterpretationStatus status = time.partial() ? BriefInterpretationStatus.PARTIAL : BriefInterpretationStatus.EXTRACTED;
        String expected = time.partial() ? "timeWindowEnd" : first(missing(parsed));
        parsed = parsed.withProgress(expected, 0, time.partial() ? time.start() : null);
        return new PlanBriefInterpretation(status, parsed, candidateFields, text,
                guidance(expected, status), true);
    }

    /** 解析并合并当前轮；Agent 候选也必须通过同一套 update seam。 */
    public UpdateResult update(PlanBrief current, String input) {
        PlanBrief base = current == null ? PlanBrief.empty() : current;
        String text = input == null ? "" : input.trim();
        if (isConfirmation(text)) {
            if (base.isComplete()) {
                PlanBrief confirmed = base.confirm();
                return result(confirmed, true, BriefInterpretationStatus.EXTRACTED, text, "", List.of());
            }
            return result(base.withProgress(first(missing(base)), base.failedAttempts(), base.partialStartTime()), false,
                    BriefInterpretationStatus.PARTIAL, text, guidance(first(missing(base)), BriefInterpretationStatus.PARTIAL), missing(base));
        }
        PlanBriefInterpretation interpretation = interpret(base, text);
        if (interpretation.status() == BriefInterpretationStatus.UNRELATED) {
            return result(base, false, interpretation.status(), interpretation.evidence(), interpretation.guidance(), missing(base));
        }
        if (interpretation.status() == BriefInterpretationStatus.EXTRACTED
                || interpretation.status() == BriefInterpretationStatus.PARTIAL) {
            return result(interpretation.parsed(), false, interpretation.status(), interpretation.evidence(),
                    interpretation.guidance(), missing(interpretation.parsed()));
        }
        PlanBrief failed = base.recordFailure(base.expectedField() == null ? first(missing(base)) : base.expectedField());
        return result(failed, false, interpretation.status(), interpretation.evidence(), interpretation.guidance(),
                missing(failed), interpretation.likelyCurrentField());
    }

    /** 将结构化 Agent 返回的候选字段重新交给 Java 规则解释和校验。 */
    public UpdateResult applyAgentCandidate(PlanBrief current, Map<String, List<String>> candidateFields, String evidence) {
        PlanBrief base = current == null ? PlanBrief.empty() : current;
        if (candidateFields == null || candidateFields.isEmpty()) {
            PlanBrief failed = base.recordFailure(null);
            return result(failed, false, BriefInterpretationStatus.INVALID, evidence, guidance(failed.expectedField(), BriefInterpretationStatus.INVALID), missing(failed));
        }
        for (Map.Entry<String, List<String>> entry : candidateFields.entrySet()) {
            if (!isAllowedCandidateField(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                PlanBrief failed = base.recordFailure(base.expectedField());
                return result(failed, false, BriefInterpretationStatus.INVALID, evidence,
                        guidance(base.expectedField(), BriefInterpretationStatus.INVALID), missing(failed));
            }
        }
        if (candidateFields.containsKey("timeEnd") && !candidateFields.containsKey("timeStart")
                && base.partialStartTime() == null) {
            PlanBrief failed = base.recordFailure(base.expectedField());
            return result(failed, false, BriefInterpretationStatus.INVALID, evidence,
                    guidance(base.expectedField(), BriefInterpretationStatus.INVALID), missing(failed));
        }
        PlanBriefInterpretation parsed = interpret(base, candidateText(candidateFields, base));
        if (parsed.status() == BriefInterpretationStatus.UNRELATED || parsed.status() == BriefInterpretationStatus.AMBIGUOUS
                || parsed.status() == BriefInterpretationStatus.INVALID) {
            PlanBrief failed = base.recordFailure(base.expectedField());
            return result(failed, false, BriefInterpretationStatus.INVALID, evidence,
                    guidance(base.expectedField(), BriefInterpretationStatus.INVALID), missing(failed));
        }
        PlanBrief candidate = parsed.parsed();
        BriefInterpretationStatus status = candidate.timeWindow() == null && candidate.partialStartTime() != null
                ? BriefInterpretationStatus.PARTIAL : BriefInterpretationStatus.EXTRACTED;
        candidate = candidate.withProgress(status == BriefInterpretationStatus.PARTIAL ? "timeWindowEnd" : first(missing(candidate)),
                0, candidate.partialStartTime());
        return result(candidate, false, status, evidence, guidance(candidate.expectedField(), status), missing(candidate));
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
        if (brief.timeWindow() == null) missing.add(brief.partialStartTime() == null ? "timeWindow" : "timeWindowEnd");
        return List.copyOf(missing);
    }

    public String question(List<String> missing) {
        if (missing == null || missing.isEmpty()) return "请确认上面的训练偏好。";
        return guidance(missing.get(0), BriefInterpretationStatus.PARTIAL);
    }

    public String summary(PlanBrief brief) {
        String days = brief.trainingDays().stream().map(this::dayLabel).reduce((a, b) -> a + "、" + b).orElse("未定");
        String window = brief.timeWindow() == null
                ? (brief.partialStartTime() == null ? "未定" : brief.partialStartTime() + "起，未定结束时间")
                : brief.timeWindow().start() + "-" + brief.timeWindow().end();
        return "训练目标：" + value(brief.trainingGoal()) + "；部位：" + join(brief.bodyParts())
                + "；器械：" + join(brief.equipment()) + "；难度：" + value(brief.difficulty())
                + "；目标周：" + value(brief.weekStart()) + "；训练日：" + days + "；时间：" + window;
    }

    private PlanBrief merge(PlanBrief base, String goal, List<String> bodyParts, List<String> equipment,
                             String difficulty, LocalDate weekStart, List<DayOfWeek> days, TimeParse time,
                             Map<String, List<String>> constraints) {
        return new PlanBrief(goal == null ? base.trainingGoal() : goal,
                bodyParts.isEmpty() ? base.bodyParts() : bodyParts,
                equipment.isEmpty() ? base.equipment() : equipment,
                difficulty == null ? base.difficulty() : difficulty,
                weekStart == null ? base.weekStart() : weekStart,
                days.isEmpty() ? base.trainingDays() : days,
                time.range() == null ? (time.partial() ? null : base.timeWindow()) : time.range(),
                mergeHardConstraints(base.hardConstraints(), constraints), false, base.confirmationVersion(), null,
                null, 0, time.partial() ? time.start() : null);
    }

    private DayParse parseDays(String text) {
        TreeSet<DayOfWeek> days = new TreeSet<>(Comparator.comparingInt(DayOfWeek::getValue));
        Matcher range = DAY_RANGE.matcher(text);
        if (range.find()) {
            DayOfWeek start = dayOf(range.group(1));
            DayOfWeek end = dayOf(range.group(2));
            if (start == null || end == null || start.getValue() > end.getValue()) return new DayParse(List.of(), true);
            for (int i = start.getValue(); i <= end.getValue(); i++) days.add(DayOfWeek.of(i));
            return checkDeclaredCount(text, days);
        }
        Matcher prefixed = PREFIXED_DAYS.matcher(text);
        while (prefixed.find()) {
            for (char value : prefixed.group(1).toCharArray()) {
                DayOfWeek day = dayOf(String.valueOf(value));
                if (day != null) days.add(day);
            }
        }
        DAY_NAMES.forEach((label, day) -> { if (text.contains(label)) days.add(day); });
        Matcher countAndCompact = Pattern.compile("(?:[1-7]|[一二三四五六七])天[，,、\\s]*([一二三四五六日天]{2,7})").matcher(text);
        if (countAndCompact.find()) {
            for (char value : countAndCompact.group(1).toCharArray()) {
                DayOfWeek day = dayOf(String.valueOf(value));
                if (day != null) days.add(day);
            }
        }
        if (days.isEmpty()) {
            Matcher compact = COMPACT_DAYS.matcher(text);
            while (compact.find()) {
                String value = compact.group(1);
                for (char dayValue : value.toCharArray()) {
                    DayOfWeek day = dayOf(String.valueOf(dayValue));
                    if (day != null) days.add(day);
                }
            }
        }
        return checkDeclaredCount(text, days);
    }

    private DayParse checkDeclaredCount(String text, Set<DayOfWeek> days) {
        Matcher count = DECLARED_DAY_COUNT.matcher(text);
        if (count.find()) {
            int expected = count.group(1) == null ? chineseNumber(count.group(2)) : Integer.parseInt(count.group(1));
            return new DayParse(List.copyOf(days), expected != days.size());
        }
        return new DayParse(List.copyOf(days), false);
    }

    private TimeParse parseWindow(String text, PlanBrief base) {
        Matcher numeric = NUMERIC_TIME_RANGE.matcher(text);
        if (numeric.find()) {
            LocalTime start = LocalTime.of(Integer.parseInt(numeric.group(1)), Integer.parseInt(numeric.group(2)));
            LocalTime end = LocalTime.of(Integer.parseInt(numeric.group(3)), Integer.parseInt(numeric.group(4)));
            return start.isBefore(end) ? new TimeParse(new TrainingTimeWindow(start, end), null, end, false, false)
                    : new TimeParse(null, null, null, false, true);
        }
        Matcher han = HAN_TIME_RANGE.matcher(text);
        if (han.find()) {
            String period = timePeriod(han.group(1));
            LocalTime start = parseTimePoint(han.group(1), null);
            LocalTime end = parseTimePoint(han.group(2), period);
            return start != null && end != null && start.isBefore(end)
                    ? new TimeParse(new TrainingTimeWindow(start, end), null, end, false, false)
                    : new TimeParse(null, null, null, false, true);
        }
        Matcher bareStart = HAN_TIME_RANGE_BARE_START.matcher(text);
        if (bareStart.find()) {
            String period = timePeriod(bareStart.group(1));
            LocalTime start = parseBareTimePoint(bareStart.group(1), null);
            LocalTime end = parseTimePoint(bareStart.group(2), period);
            return start != null && end != null && start.isBefore(end)
                    ? new TimeParse(new TrainingTimeWindow(start, end), null, end, false, false)
                    : new TimeParse(null, null, null, false, true);
        }
        String point = findSingleTimePoint(text);
        if (point != null) {
            LocalTime parsed = parseTimePoint(point, null);
            if (parsed == null) return new TimeParse(null, null, null, false, true);
            if (base != null && base.partialStartTime() != null && isEndOnlyExpression(text)) {
                LocalTime end = parseTimePoint(point, continuationPeriod(base.partialStartTime()));
                return end != null && base.partialStartTime().isBefore(end)
                        ? new TimeParse(new TrainingTimeWindow(base.partialStartTime(), end), null, end, false, false)
                        : new TimeParse(null, null, null, false, true);
            }
            return new TimeParse(null, parsed, null, true, false);
        }
        return new TimeParse(null, null, null, false, false);
    }

    private String findSingleTimePoint(String text) {
        Matcher numeric = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)").matcher(text);
        if (numeric.find()) return numeric.group();
        Matcher han = HAN_TIME_POINT.matcher(text);
        return han.find() ? han.group() : null;
    }

    private LocalTime parseTimePoint(String point) {
        return parseTimePoint(point, null);
    }

    private LocalTime parseTimePoint(String point, String inheritedPeriod) {
        Matcher numeric = Pattern.compile("^([01]?\\d|2[0-3])[:：]([0-5]\\d)$").matcher(point.trim());
        if (numeric.matches()) return LocalTime.of(Integer.parseInt(numeric.group(1)), Integer.parseInt(numeric.group(2)));
        Matcher han = HAN_TIME_POINT.matcher(point.trim());
        if (!han.matches()) return null;
        int hour = chineseNumber(han.group(2));
        if (hour > 23) return null;
        String period = han.group(1) == null ? inheritedPeriod : han.group(1);
        if (("下午".equals(period) || "晚上".equals(period) || "夜里".equals(period)) && hour < 12) hour += 12;
        if ("中午".equals(period) && hour < 11) hour += 12;
        int minute = switch (han.group(3) == null ? "" : han.group(3)) {
            case "半" -> 30;
            case "一刻" -> 15;
            case "三刻" -> 45;
            default -> 0;
        };
        return LocalTime.of(hour, minute);
    }

    private LocalTime parseBareTimePoint(String point, String inheritedPeriod) {
        Matcher han = HAN_BARE_TIME_POINT.matcher(point.trim());
        if (!han.matches()) return null;
        int hour = chineseNumber(han.group(2));
        if (hour > 23) return null;
        String period = han.group(1) == null ? inheritedPeriod : han.group(1);
        if (("下午".equals(period) || "晚上".equals(period) || "夜里".equals(period)) && hour < 12) hour += 12;
        if ("中午".equals(period) && hour < 11) hour += 12;
        return LocalTime.of(hour, 0);
    }

    private String timePeriod(String point) {
        Matcher matcher = HAN_TIME_POINT.matcher(point.trim());
        if (matcher.matches()) return matcher.group(1);
        Matcher bare = HAN_BARE_TIME_POINT.matcher(point.trim());
        return bare.matches() ? bare.group(1) : null;
    }

    private String continuationPeriod(LocalTime start) {
        return start.getHour() >= 12 ? "下午" : null;
    }

    private boolean isEndOnlyExpression(String text) {
        return text != null && text.trim().matches("^(?:到|至|[-—~～])\\s*.*$");
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

    private Map<String, List<String>> parseHardConstraints(String text) {
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        List<String> excludedParts = new ArrayList<>();
        if (text.contains("不要练胸") || text.contains("不练胸")) excludedParts.add("胸");
        if (text.contains("不要练腿") || text.contains("不练腿")) excludedParts.add("腿");
        if (!excludedParts.isEmpty()) constraints.put("excludeBodyParts", List.copyOf(excludedParts));
        List<String> excludedEquipment = new ArrayList<>();
        if (text.contains("不要用哑铃") || text.contains("不用哑铃")) excludedEquipment.add("哑铃");
        if (text.contains("不要用杠铃") || text.contains("不用杠铃")) excludedEquipment.add("杠铃");
        if (!excludedEquipment.isEmpty()) constraints.put("excludeEquipment", List.copyOf(excludedEquipment));
        Matcher exercise = EXCLUDED_EXERCISE.matcher(text);
        List<String> excludedExercises = new ArrayList<>();
        while (exercise.find()) excludedExercises.add(exercise.group(1));
        if (!excludedExercises.isEmpty()) constraints.put("excludeExercises", List.copyOf(excludedExercises));
        return constraints;
    }

    private void rejectUnsupportedHardConstraints(String text) {
        String remaining = text;
        for (String supported : List.of("不要练胸", "不练胸", "不要练腿", "不练腿", "不要用哑铃", "不用哑铃", "不要用杠铃", "不用杠铃")) {
            remaining = remaining.replace(supported, "");
        }
        remaining = EXCLUDED_EXERCISE.matcher(remaining).replaceAll("");
        if (List.of("避免", "不能", "不要", "不练", "不用", "不做", "排除").stream().anyMatch(remaining::contains)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST,
                    "该硬约束暂不支持，请改为排除具体部位、动作、器械或提供明确训练时段");
        }
    }

    private boolean isUnrelated(String text) {
        return List.of("吃什么", "餐食", "早餐", "午餐", "晚餐", "饮食", "作息", "睡眠", "睡多久", "几点睡", "咖啡",
                "推荐电影", "天气", "写代码", "讲个笑话").stream().anyMatch(text::contains);
    }

    private String guidance(String field, BriefInterpretationStatus status) {
        if (status == BriefInterpretationStatus.AMBIGUOUS) {
            return switch (field == null ? "" : field) {
                case "trainingDays" -> "训练天数和日期数量对不上，请明确说“二四六”或“周一到周三”，并保证天数一致。";
                case "timeWindow" -> "时间范围不明确或顺序相反，请说“下午六点到七点”或“18:00-19:00”。";
                default -> "这句话有两种以上理解，请按当前字段给出一个明确值。";
            };
        }
        return switch (field == null ? "" : field) {
            case "trainingGoal" -> "训练主要想达成什么目标？例如增肌、减脂、耐力或保持健康。";
            case "bodyParts" -> "请说训练部位，例如胸、背、腿、核心或全身。";
            case "equipment" -> "请说可用器械，例如徒手、哑铃、弹力带或器械。";
            case "difficulty" -> "请说训练难度，例如入门、进阶或挑战。";
            case "weekStart" -> "请给目标周的周一日期，例如 2026-08-24。";
            case "trainingDays" -> "请说训练日，例如“三天，二四六”“一三五”或“周一到周三”。";
            case "timeWindow" -> "请说完整时间段，例如“下午六点至七点”“六点半到七点一刻”或“19:00-20:00”。";
            case "timeWindowEnd" -> "已记下开始时间，请补充结束时间，例如“到六点”或“至七点”。";
            default -> status == BriefInterpretationStatus.PARTIAL ? "已记下部分信息，请继续补充当前字段。" : "暂时没识别出当前字段，请按示例格式补充。";
        };
    }

    private UpdateResult result(PlanBrief brief, boolean confirmed, BriefInterpretationStatus status, String evidence,
                                String guidance, List<String> missing) {
        return result(brief, confirmed, status, evidence, guidance, missing,
                status == BriefInterpretationStatus.AMBIGUOUS);
    }

    private UpdateResult result(PlanBrief brief, boolean confirmed, BriefInterpretationStatus status, String evidence,
                                String guidance, List<String> missing, boolean agentEligible) {
        return new UpdateResult(brief, confirmed, missing, status, evidence, guidance,
                agentEligible);
    }

    private PlanBriefInterpretation invalid(PlanBrief base, String guidance, boolean likelyCurrentField) {
        return new PlanBriefInterpretation(BriefInterpretationStatus.INVALID, base, Map.of(), "", guidance, likelyCurrentField);
    }

    private boolean isAllowedCandidateField(String field) {
        return Set.of("trainingGoal", "bodyParts", "equipment", "difficulty", "weekStart", "trainingDays", "timeStart", "timeEnd").contains(field);
    }

    private String candidateText(Map<String, List<String>> fields, PlanBrief base) {
        StringBuilder text = new StringBuilder();
        appendCandidate(text, fields, "trainingGoal");
        appendCandidate(text, fields, "bodyParts");
        appendCandidate(text, fields, "equipment");
        appendCandidate(text, fields, "difficulty");
        appendCandidate(text, fields, "weekStart");
        if (fields.containsKey("trainingDays")) {
            text.append(String.join("、", fields.get("trainingDays").stream().map(this::dayCandidateText).toList())).append(' ');
        }
        String start = first(fields.get("timeStart"));
        String end = first(fields.get("timeEnd"));
        if (start != null && end != null) text.append(start).append('-').append(end);
        else if (start != null) text.append(start);
        else if (end != null) text.append("到").append(end);
        return text.toString().trim();
    }

    private void appendCandidate(StringBuilder text, Map<String, List<String>> fields, String key) {
        List<String> values = fields.get(key);
        if (values != null && !values.isEmpty()) text.append(String.join("、", values)).append(' ');
    }

    private String dayCandidateText(String value) {
        if (value == null) return "";
        return switch (value.trim().toUpperCase()) {
            case "MONDAY", "周一", "星期一", "一" -> "周一";
            case "TUESDAY", "周二", "星期二", "二" -> "周二";
            case "WEDNESDAY", "周三", "星期三", "三" -> "周三";
            case "THURSDAY", "周四", "星期四", "四" -> "周四";
            case "FRIDAY", "周五", "星期五", "五" -> "周五";
            case "SATURDAY", "周六", "星期六", "六" -> "周六";
            case "SUNDAY", "周日", "星期日", "周天", "日", "天" -> "周日";
            default -> value;
        };
    }

    private void put(Map<String, List<String>> target, String key, String value) { if (value != null && !value.isBlank()) target.put(key, List.of(value)); }
    private void put(Map<String, List<String>> target, String key, List<String> values) { if (values != null && !values.isEmpty()) target.put(key, List.copyOf(values)); }
    private String first(List<String> values) { return values == null || values.isEmpty() ? null : values.get(0); }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }
    private String value(Object value) { return value == null ? "未定" : String.valueOf(value); }
    private String join(List<String> values) { return values == null || values.isEmpty() ? "未定" : String.join("、", values); }
    private String dayLabel(DayOfWeek day) { return "周" + "一二三四五六日".charAt(day.getValue() - 1); }
    private DayOfWeek dayOf(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value) {
            case "一" -> DayOfWeek.MONDAY; case "二" -> DayOfWeek.TUESDAY; case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY; case "五" -> DayOfWeek.FRIDAY; case "六" -> DayOfWeek.SATURDAY;
            case "日", "天" -> DayOfWeek.SUNDAY; default -> DAY_NAMES.get(value);
        };
    }
    private int chineseNumber(String value) {
        if (value == null || value.isBlank()) return -1;
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        if (value.length() == 1) return switch (value) {
            case "零", "〇" -> 0; case "一" -> 1; case "二", "两" -> 2; case "三" -> 3; case "四" -> 4;
            case "五" -> 5; case "六" -> 6; case "七" -> 7; case "八" -> 8; case "九" -> 9; case "十" -> 10; default -> -1;
        };
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int prefix = ten == 0 ? 1 : chineseNumber(value.substring(0, ten));
            int suffix = ten == value.length() - 1 ? 0 : chineseNumber(value.substring(ten + 1));
            return prefix < 0 || suffix < 0 ? -1 : prefix * 10 + suffix;
        }
        return -1;
    }

    private Map<String, List<String>> mergeHardConstraints(Map<String, List<String>> current, Map<String, List<String>> updates) {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        if (current != null) current.forEach((key, values) -> merged.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(values));
        if (updates != null) updates.forEach((key, values) -> merged.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(values));
        Map<String, List<String>> result = new LinkedHashMap<>();
        merged.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private boolean likelyCurrentField(String text, PlanBrief base) {
        String field = base.expectedField() == null ? first(missing(base)) : base.expectedField();
        return switch (field == null ? "" : field) {
            case "trainingGoal" -> containsAny(text, "增肌", "减脂", "减肥", "耐力", "力量", "柔韧", "健康", "塑形");
            case "bodyParts" -> containsAny(text, "胸", "背", "腿", "肩", "手臂", "胳膊", "核心", "腹", "臀", "全身");
            case "equipment" -> containsAny(text, "徒手", "自重", "哑铃", "杠铃", "弹力带", "壶铃", "器械");
            case "difficulty" -> containsAny(text, "入门", "新手", "初学", "进阶", "挑战");
            case "weekStart" -> ISO_DATE.matcher(text).find() || containsAny(text, "本周", "这周", "下周", "周一开始");
            case "trainingDays" -> containsAny(text, "周", "星期", "几天", "天训练") || COMPACT_DAYS.matcher(text).find();
            case "timeWindow", "timeWindowEnd" -> containsAny(text, "点", "时", "上午", "下午", "晚上", "夜里", "到", "至", ":");
            default -> false;
        };
    }

    private boolean containsAny(String text, String... values) {
        return text != null && java.util.Arrays.stream(values).anyMatch(text::contains);
    }

    private boolean isConfirmation(String text) {
        return text.contains("确认训练偏好") || text.equals("确认") || text.contains("确认简报") || text.contains("按这个生成");
    }

    private record DayParse(List<DayOfWeek> days, boolean ambiguous) { }
    private record TimeParse(TrainingTimeWindow range, LocalTime start, LocalTime end, boolean partial, boolean ambiguous) { }

    public record UpdateResult(PlanBrief brief, boolean confirmedNow, List<String> missingFields,
                               BriefInterpretationStatus status, String evidence, String guidance,
                               boolean agentEligible) { }
}
