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
 * 简报补充回路（规格 v3.2）+ 餐食标签加固规格：可选偏好（菜系/餐食类型/口味营养/便利性）
 * 解析必须先于 isUnrelated、looksLikeMealInput 等旧启发式执行；菜系与餐食类型分别委托
 * 确定性的 {@link MealCuisineIntentParser} 与 {@link MealFoodTypeIntentParser}
 * （不依赖模型、不参与路由）；受支持值经归一器别名表规范，未支持值只由解析器产生并按
 * "field:value" 稳定键登记 unsupportedPreferences，不参与筛选或生成；
 * 热量目标永不被口味值覆盖；菜系/餐食类型/口味均多选，仅便利性为单选。
 */
@Service
public class MealPlanBriefService {

    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");

    /** “换成/改为”等显式修改语义：列表字段（菜系/类型/口味）仅在出现修改词时重建集合。 */
    private static final String[] CHANGE_WORDS = {"换成", "改为", "改成", "调整为", "修改为", "换一"};

    /** 健康目标白名单：healthGoal 只承载这四个规范值。 */
    private static final List<String> GOAL_WHITELIST = List.of("减脂", "增肌", "维持健康", "均衡");

    /** 归一器：别名表是受支持词汇的唯一事实来源（无 I/O，纯词表）。 */
    private final HealthInputNormalizer normalizer;
    private final MealCuisineIntentParser cuisineParser;
    private final MealFoodTypeIntentParser foodTypeParser;

    /** Spring 装配入口。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MealPlanBriefService(HealthInputNormalizer normalizer, MealCuisineIntentParser cuisineParser,
                                MealFoodTypeIntentParser foodTypeParser) {
        this.normalizer = normalizer;
        this.cuisineParser = cuisineParser;
        this.foodTypeParser = foodTypeParser;
    }

    /** 测试与旧调用兼容入口：内部持有等价的纯词表归一器与解析器。 */
    public MealPlanBriefService() {
        this(new HealthInputNormalizer(), new MealCuisineIntentParser(new HealthInputNormalizer()),
                new MealFoodTypeIntentParser(new HealthInputNormalizer()));
    }

    public UpdateResult update(MealPlanBrief current, String input) {
        MealPlanBrief base = current == null ? MealPlanBrief.empty() : current;
        String text = input == null ? "" : input.trim();

        // 1) 确定性受限菜系解析：必须先于 isUnrelated / looksLikeMealInput 旧启发式，
        //    可选偏好命中本身足以使输入进入餐食简报处理器
        MealCuisineIntentParser.CuisineParse cuisineParse = cuisineParser.parse(text);

        // 2) 餐食类型（显式形态 + 未支持诚实通道）、口味/营养偏好与便利性（经归一器别名表规范）
        MealFoodTypeIntentParser.FoodTypeParse foodTypeParse = foodTypeParser.parse(text);
        List<String> tasteValues = parseTastePreferences(text);
        List<String> convenienceValues = parseAliases("convenience", text);
        String convenience = convenienceValues.isEmpty() ? null : convenienceValues.get(0);
        String healthGoal = parseGoal(text);

        // 3) 旧启发式：完全没有任何字段命中时保持原有 UNRELATED/INVALID 语义
        boolean anyOptionalHit = cuisineParse.matched() || foodTypeParse.matched()
                || !tasteValues.isEmpty() || convenience != null;
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

        // 4) 可选偏好合并：菜系/类型/口味均支持多值；“换成/改为”重建集合
        MealPlanBrief merged = base;
        List<String> unsupported = new ArrayList<>(base.unsupportedPreferences());
        String conflictNote = null;
        if (cuisineParse.matched()) {
            List<String> parsedCuisines = mergeAppend(cuisineParse.supported(), cuisineParse.unsupported());
            List<String> nextCuisines = hasChangeIntent(text)
                    ? parsedCuisines
                    : mergeAppend(base.cuisines(), parsedCuisines);
            cuisineParse.unsupported().forEach(value -> unsupported.add("cuisine:" + value));
            merged = merged.withOptional(nextCuisines, null, null, null, null);
        }
        if (foodTypeParse.matched()) {
            List<String> parsedFoodTypes = mergeAppend(foodTypeParse.supported(), foodTypeParse.unsupported());
            List<String> nextFoodTypes = hasChangeIntent(text)
                    ? parsedFoodTypes : mergeAppend(base.foodTypes(), parsedFoodTypes);
            foodTypeParse.unsupported().forEach(value -> unsupported.add("foodType:" + value));
            merged = merged.withOptional(null, nextFoodTypes, null, null, null);
        }
        if (!tasteValues.isEmpty()) {
            boolean changeIntent = hasChangeIntent(text);
            List<String> nextTaste = changeIntent ? tasteValues
                    : mergeAppend(base.tastePreferences(), tasteValues);
            merged = merged.withOptional(null, null, nextTaste, null, null);
        }
        if (convenienceValues.size() > 1 && !hasChangeIntent(text)) {
            String retained = base.convenience();
            if (retained == null || retained.isBlank()) {
                retained = convenience;
                merged = merged.withOptional(null, null, null, retained, null);
            }
            conflictNote = "烹饪时长一次只能选择一个；当前保留“" + retained
                    + "”，如需修改请使用“换成/改为”。可选值：" + String.join("、", convenienceValues);
        } else if (convenience != null) {
            merged = merged.withOptional(null, null, null, convenience, null);
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

    private List<String> parseAliases(String slot, String text) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectAliases(slot, text, values);
        return List.copyOf(values);
    }

    /** 解析口味与营养偏好：口味规范值与热量目标之外的健康目标规范值统一进 tastePreferences。 */
    private List<String> parseTastePreferences(String text) {
        LinkedHashSet<String> values = new LinkedHashSet<>(parseAliases("taste", text));
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
        if (!value.cuisines().isEmpty()) {
            summary.append("；菜系：").append(String.join("、", value.cuisines()));
        }
        if (!value.foodTypes().isEmpty()) {
            summary.append("；餐食类型：").append(String.join("、", value.foodTypes()));
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
        boolean hasSupportedCuisine = value.cuisines().stream().anyMatch(cuisine ->
                !value.unsupportedPreferences().contains("cuisine:" + cuisine));
        if (!hasSupportedCuisine) {
            items.add(new SupplementableItem("cuisines", "菜系",
                    List.of("粤菜", "川菜"), false));
        }
        boolean hasSupportedFoodType = value.foodTypes().stream().anyMatch(foodType ->
                !value.unsupportedPreferences().contains("foodType:" + foodType));
        if (!hasSupportedFoodType) {
            items.add(new SupplementableItem("foodTypes", "餐食类型",
                    List.of("素食", "轻食"), false));
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
        return base + "还可以补充：菜系（如粤菜、川菜）、餐食类型（如素食、轻食）、口味（如清淡、高蛋白）、烹饪时长（如烹饪时间短）；推荐时还可补充用餐场景、当下心情、过敏或忌口。";
    }

    public record UpdateResult(MealPlanBrief brief, BriefInterpretationStatus status,
                               List<String> missingFields, String guidance, boolean agentEligible) {
        public UpdateResult {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }
    }
}
