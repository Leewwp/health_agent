package com.diet.health.plan;

import com.diet.health.intent.HealthInputNormalizer;
import com.diet.health.model.SupplementableItem;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 餐食计划简报的独立解析、确认和字段指引服务。
 * <p>
 * 简报补充回路（规格 v3.2）：可选偏好（菜系/口味营养/便利性）解析必须先于
 * isUnrelated、looksLikeMealInput 等旧启发式执行；菜系解析委托确定性的
 * {@link MealCuisineIntentParser}（不依赖模型、不参与路由）；受支持值经归一器
 * 别名表规范，未支持值只由该解析器产生并登记 unsupportedPreferences；
 * 热量目标永不被口味值覆盖；菜系/便利性为单选，对齐既有难度单选冲突语义。
 */
@Service
public class MealPlanBriefService {

    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");

    /** “换成/改为”等显式修改语义：单选字段仅在出现时才允许覆盖已有值。 */
    private static final String[] CHANGE_WORDS = {"换成", "改为", "改成", "调整为", "修改为", "换一"};

    /** 健康目标白名单：healthGoal 只承载这四个规范值。 */
    private static final List<String> GOAL_WHITELIST = List.of("减脂", "增肌", "维持健康", "均衡");

    /** 归一器：别名表是受支持词汇的唯一事实来源（无 I/O，纯词表）。 */
    private final HealthInputNormalizer normalizer;
    private final MealCuisineIntentParser cuisineParser;

    /** Spring 装配入口。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MealPlanBriefService(HealthInputNormalizer normalizer, MealCuisineIntentParser cuisineParser) {
        this.normalizer = normalizer;
        this.cuisineParser = cuisineParser;
    }

    /** 测试与旧调用兼容入口：内部持有等价的纯词表归一器与解析器。 */
    public MealPlanBriefService() {
        this(new HealthInputNormalizer(), new MealCuisineIntentParser(new HealthInputNormalizer()));
    }

    public UpdateResult update(MealPlanBrief current, String input) {
        MealPlanBrief base = current == null ? MealPlanBrief.empty() : current;
        String text = input == null ? "" : input.trim();

        // 1) 确定性受限菜系解析：必须先于 isUnrelated / looksLikeMealInput 旧启发式，
        //    可选偏好命中本身足以使输入进入餐食简报处理器
        MealCuisineIntentParser.CuisineParse cuisineParse = cuisineParser.parse(text);

        // 2) 口味/营养偏好与便利性（经归一器别名表规范；未支持偏好不在此产生）
        List<String> tasteValues = parseTastePreferences(text);
        String convenience = parseSingleAlias("convenience", text);
        String healthGoal = parseGoal(text);

        // 3) 旧启发式：完全没有任何字段命中时保持原有 UNRELATED/INVALID 语义
        boolean anyOptionalHit = cuisineParse.matched() || !tasteValues.isEmpty() || convenience != null;
        if (!anyOptionalHit && isUnrelated(text)) {
            return new UpdateResult(base, BriefInterpretationStatus.UNRELATED,
                    missing(base), "已保留当前未完成的餐食简报，先处理你刚才的新话题。", false);
        }

        LocalDate weekStart = parseWeekStart(text);
        List<String> mealTimes = parseMealTimes(text);
        if (weekStart == null && mealTimes.isEmpty() && healthGoal == null && !anyOptionalHit) {
            return new UpdateResult(base, BriefInterpretationStatus.INVALID,
                    missing(base), guidanceWithSupplementable(firstMissing(base)), looksLikeMealInput(text));
        }

        // 4) 可选偏好合并：菜系/便利性单选冲突语义对齐难度单选；口味为多值追加去重
        MealPlanBrief merged = base;
        List<String> unsupported = new ArrayList<>(base.unsupportedPreferences());
        String conflictNote = null;
        if (cuisineParse.matched()) {
            CuisineApply cuisineApply = applySingleSelect("cuisine", base.cuisine(), text,
                    cuisineParse.supported(), cuisineParse.unsupported(), true);
            unsupported = cuisineApply.unsupported;
            merged = merged.withOptional(cuisineApply.value, null, null, null);
            conflictNote = cuisineApply.conflictNote;
        }
        if (!tasteValues.isEmpty()) {
            boolean changeIntent = hasChangeIntent(text);
            List<String> nextTaste = changeIntent ? tasteValues
                    : mergeAppend(base.tastePreferences(), tasteValues);
            merged = merged.withOptional(null, nextTaste, null, null);
        }
        if (convenience != null) {
            CuisineApply convenienceApply = applySingleSelect("convenience", base.convenience(), text,
                    List.of(convenience), List.of(), false);
            merged = merged.withOptional(null, null, convenienceApply.value, null);
            conflictNote = conflictNote == null ? convenienceApply.conflictNote : conflictNote;
        }
        if (!unsupported.equals(base.unsupportedPreferences())) {
            merged = merged.withUnsupportedPreferences(unsupported);
        }
        merged = merged.withValues(weekStart, mealTimes, healthGoal);
        String guidance = conflictNote != null && !conflictNote.isBlank()
                ? conflictNote
                : (merged.isComplete() ? "" : guidanceWithSupplementable(firstMissing(merged)));
        return new UpdateResult(merged, BriefInterpretationStatus.EXTRACTED,
                missing(merged), guidance, true);
    }

    /** 单选字段应用结果：value 最终值、unsupported 更新后的未支持集合、conflictNote 冲突提示。 */
    private record CuisineApply(String value, List<String> unsupported, String conflictNote) {
    }

    /**
     * 菜系/便利性单选冲突语义（对齐难度单选）：
     * 已有值且无“换成/改为”语义 → 保留并提示只能选一个；空字段恰有一个受支持值 → 采用它、
     * 其余未支持值登记；多个受支持值 → 不猜测，返回可选列表要求重选。
     * cuisine 允许把形态内的未支持原值写入字段（如“中餐”）；convenience 只接受规范值。
     */
    private CuisineApply applySingleSelect(String field, String currentValue, String text,
                                           List<String> supported, List<String> unsupportedRaw,
                                           boolean allowUnsupportedValue) {
        List<String> unsupported = new ArrayList<>();
        String fieldKey = "cuisine".equals(field) ? "cuisine" : "convenience";
        unsupportedRaw.forEach(value -> unsupported.add(fieldKey + ":" + value));
        boolean changeIntent = hasChangeIntent(text);
        String note = null;
        String value = currentValue;
        if (supported.size() > 1) {
            if (currentValue == null || currentValue.isBlank()) {
                note = "一次只能选择一个" + fieldLabel(field) + "。检测到：" + String.join("、", supported)
                        + "，请重新说明一个，当前可选：" + optionsPreview(field) + "。";
            } else if (!changeIntent) {
                note = "一次只能选择一个" + fieldLabel(field) + "。已保留当前“" + currentValue
                        + "”，如需修改请说“换成X”。";
            } else {
                note = "一次只能选择一个" + fieldLabel(field) + "。检测到：" + String.join("、", supported)
                        + "，请只说明一个新值。";
            }
            return new CuisineApply(currentValue, unsupported, note);
        }
        String next = null;
        if (supported.size() == 1) {
            next = supported.get(0);
        } else if (allowUnsupportedValue && unsupportedRaw.size() == 1) {
            // 空字段仅有未支持值（如“中餐”）：原值写入字段并登记未支持集合
            next = unsupportedRaw.get(0);
        }
        if (next == null) {
            return new CuisineApply(currentValue, unsupported, note);
        }
        if (currentValue != null && !currentValue.isBlank() && !currentValue.equals(next) && !changeIntent) {
            return new CuisineApply(currentValue, unsupported,
                    "一次只能选择一个" + fieldLabel(field) + "。已保留当前“" + currentValue + "”，如需修改请说“换成" + next + "”。");
        }
        return new CuisineApply(next, unsupported, note);
    }

    private String fieldLabel(String field) {
        return "cuisine".equals(field) ? "菜系" : "烹饪时长";
    }

    private String optionsPreview(String field) {
        return "cuisine".equals(field) ? String.join("、", cuisineParser.supportedCuisines())
                : "快速、慢享等";
    }

    /** 解析口味与营养偏好：口味规范值与热量目标之外的健康目标规范值统一进 tastePreferences。 */
    private List<String> parseTastePreferences(String text) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectAliases("taste", text, values);
        collectAliases("healthGoal", text, values);
        // healthGoal 白名单之外的健康目标规范值属于口味/营养偏好
        values.removeIf(GOAL_WHITELIST::contains);
        return List.copyOf(values);
    }

    private void collectAliases(String slot, String text, LinkedHashSet<String> target) {
        List<Map.Entry<String, String>> aliases = normalizer.slotAliases(slot).entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .toList();
        // 长别名优先并消费已匹配片段，避免“微辣”同时命中“辣”这类子串别名
        String remaining = text;
        for (Map.Entry<String, String> alias : aliases) {
            if (remaining.contains(alias.getKey())) {
                target.add(alias.getValue());
                remaining = remaining.replace(alias.getKey(), "、");
            }
        }
    }

    /** 单值槽位：取第一个命中的规范值（别名长短优先避免子串误伤）。 */
    private String parseSingleAlias(String slot, String text) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectAliases(slot, text, values);
        return values.isEmpty() ? null : values.iterator().next();
    }

    private boolean hasChangeIntent(String text) {
        for (String word : CHANGE_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private List<String> mergeAppend(List<String> current, List<String> additions) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(current);
        merged.addAll(additions);
        return List.copyOf(merged);
    }

    public List<String> missing(MealPlanBrief brief) {
        MealPlanBrief value = brief == null ? MealPlanBrief.empty() : brief;
        List<String> missing = new ArrayList<>();
        if (value.weekStart() == null) missing.add("weekStart");
        if (value.mealTimes().isEmpty()) missing.add("mealTimes");
        if (value.healthGoal() == null || value.healthGoal().isBlank()) missing.add("healthGoal");
        return List.copyOf(missing);
    }

    /** 简报摘要：包含可选偏好与未支持偏好，未填项显式“未定”。 */
    public String summary(MealPlanBrief brief) {
        MealPlanBrief value = brief == null ? MealPlanBrief.empty() : brief;
        StringBuilder summary = new StringBuilder();
        summary.append("目标周：").append(value.weekStart() == null ? "未定" : value.weekStart());
        summary.append("；餐次：").append(value.mealTimes().isEmpty() ? "未定" : String.join("、", value.mealTimes()));
        summary.append("；目标：").append(value.healthGoal() == null ? "未定" : value.healthGoal());
        if (value.cuisine() != null && !value.cuisine().isBlank()) {
            summary.append("；菜系：").append(value.cuisine());
        }
        if (!value.tastePreferences().isEmpty()) {
            summary.append("；口味：").append(String.join("、", value.tastePreferences()));
        }
        if (value.convenience() != null && !value.convenience().isBlank()) {
            summary.append("；烹饪时长：").append(value.convenience());
        }
        if (!value.unsupportedPreferences().isEmpty()) {
            summary.append("；暂不支持：").append(String.join("、", value.unsupportedPreferences()));
        }
        return summary.toString();
    }

    /**
     * 可补充项（契约 {key, label, examples, filled}）：只列未填项；
     * 已填项不重复出现，改填通过“换成/改为”完成。
     */
    public List<SupplementableItem> supplementable(MealPlanBrief brief) {
        MealPlanBrief value = brief == null ? MealPlanBrief.empty() : brief;
        List<SupplementableItem> items = new ArrayList<>();
        if (value.cuisine() == null || value.cuisine().isBlank()) {
            items.add(new SupplementableItem("cuisine", "菜系",
                    List.of("川菜", "家常菜"), false));
        }
        if (value.tastePreferences().isEmpty()) {
            items.add(new SupplementableItem("tastePreferences", "口味",
                    List.of("清淡", "高蛋白"), false));
        }
        if (value.convenience() == null || value.convenience().isBlank()) {
            items.add(new SupplementableItem("convenience", "烹饪时长",
                    List.of("烹饪时间短", "快手菜"), false));
        }
        return List.copyOf(items);
    }

    /** 当前可选菜系列表（归一器别名表规范值），未支持偏好的回应附带该列表。 */
    public List<String> supportedCuisines() {
        return cuisineParser.supportedCuisines();
    }

    public boolean looksLikeMealInput(String input) {
        String text = input == null ? "" : input;
        return !parseMealTimes(text).isEmpty()
                || text.contains("餐食") || text.contains("饮食") || text.contains("吃什么")
                || text.contains("减脂") || text.contains("增肌");
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

    /** 热量目标：只接受白名单规范值，永不被“清淡、低油、高蛋白”等口味值覆盖。 */
    private String parseGoal(String text) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectAliases("healthGoal", text, values);
        for (String value : values) {
            if (GOAL_WHITELIST.contains(value)) {
                return value;
            }
        }
        if (text.contains("减脂") || text.contains("减重")) return "减脂";
        if (text.contains("增肌")) return "增肌";
        if (text.contains("维持") || text.contains("保持健康")) return "维持健康";
        if (text.contains("均衡")) return "均衡";
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
            case "healthGoal" -> "请补充餐食目标，例如减脂、增肌、维持健康或均衡饮食。";
            default -> "请补充餐食计划信息，例如“目标周下周，安排早餐、午餐和晚餐”。";
        };
    }

    /** 指引 = 字段指引 + 可补充项枚举（可行动指引，不出现内部术语）。 */
    private String guidanceWithSupplementable(String field) {
        String base = guidance(field);
        return base + "还可以补充：菜系（如川菜）、口味（如清淡、高蛋白）、烹饪时长（如烹饪时间短）。";
    }

    public record UpdateResult(MealPlanBrief brief, BriefInterpretationStatus status,
                               List<String> missingFields, String guidance, boolean agentEligible) {
        public UpdateResult {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }
    }
}
