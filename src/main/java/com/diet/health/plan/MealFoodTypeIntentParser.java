package com.diet.health.plan;

import com.diet.health.intent.HealthInputNormalizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性受限餐食类型意图解析器（餐食标签加固规格，ADR-0017）。
 * <p>
 * 与 {@link MealCuisineIntentParser} 对称：不依赖模型、不参与路由，输出只写 {@link MealPlanBrief}。
 * 显式类型形态有三种：① 标签前缀“餐食类型：X / 餐食类型是 X”（最显式，词表外原值一律诚实登记）；
 * ② 口语前缀“想吃 X”；③ 类型后缀“X 类型”。解析器先剥除否定范围（“不想吃 X”不得写入正向偏好），
 * 再按中文标点和“和/或/以及”分隔候选值；“换成/改为”等修改词从候选中剥除（替换语义由简报服务处理）。
 * 受支持值经归一器别名表规范；未命中的形态内原值登记为未支持并保留在 foodTypes，
 * 以 "foodType:<value>" 记入 unsupportedPreferences，不参与筛选或生成。
 * 模型 rawSlots 中未被别名表收录的未知值必须丢弃，只有本解析器能产生未支持原值。
 */
@Component
public class MealFoodTypeIntentParser {

    /** 显式标签前缀形态：餐食类型：X / 餐食类型是 X / 餐食类型 X；跨度含顿号/逗号/连词，由 SPLIT 切分。 */
    private static final Pattern LABEL_PREFIX =
            Pattern.compile("餐食类型\\s*[:：是]?\\s*([^。！!？?\\n]{1,24})");

    /** 口语前缀形态：想吃 X（想吃素、想吃火锅、想吃生酮……）；跨度含顿号/逗号/连词，由 SPLIT 切分。 */
    private static final Pattern WANT_PREFIX =
            Pattern.compile("想吃\\s*的?\\s*([^。！!？?\\n]{1,24})");

    /** 类型后缀形态：X 类型（如“生酮类型”“烧烤类型”），弱显式，登记前过跨槽位守卫。 */
    private static final Pattern LABEL_SUFFIX = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]{1,8})类型");

    /** 否定范围：否定词 + 短语（不含分隔标点），整体剥除不得写入正向偏好。 */
    private static final Pattern NEGATED_SPAN =
            Pattern.compile("(?:不喜欢|不想吃|不想要|不要|不吃|不用|不碰|避免|排除|别)\\s*的?\\s*([^，。；,;！!？?\\n]{1,12})");

    /** 候选值分隔：中文标点 + “和/或/以及”。 */
    private static final Pattern SPLIT = Pattern.compile("[、，,；;和或以及\\s]+");

    /** 修改词：从候选中剥除，替换语义由简报服务的 hasChangeIntent 统一处理。 */
    private static final Pattern CHANGE_WORDS = Pattern.compile("换成|改为|改成|调整为|修改为|换一");

    /** 后缀形态跨度开头赘词（如“有没有生酮类型”→“生酮”）。 */
    private static final Pattern LEADING_FILLERS =
            Pattern.compile("^(?:有没有|想要|喜欢|来点|推荐|试试|看看|想找|找个|找|要)+");

    /** 候选尾部口语后缀剥离：“的东西/的”（如“轻食的东西”→“轻食”）。 */
    private static final Pattern TRAILING_DE = Pattern.compile("(?:的东西|的)$");

    /** 跨槽位守卫覆盖的槽位：弱形态（想吃 X / X 类型）候选若属于这些槽位则不登记为类型。 */
    private static final List<String> OTHER_MEAL_SLOTS =
            List.of("mealTime", "mood", "scene", "healthGoal", "taste", "convenience", "cuisine");

    private final HealthInputNormalizer normalizer;

    public MealFoodTypeIntentParser(HealthInputNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /** 解析结果：matched 是否命中形态之一；supported 受支持规范值；unsupported 形态内未收录原值。 */
    public record FoodTypeParse(boolean matched, List<String> supported, List<String> unsupported) {

        public static FoodTypeParse notMatched() {
            return new FoodTypeParse(false, List.of(), List.of());
        }
    }

    public FoodTypeParse parse(String input) {
        String text = input == null ? "" : input.replaceAll("\\s+", "").trim();
        if (text.isEmpty()) {
            return FoodTypeParse.notMatched();
        }
        // 先剥除否定范围；受支持值来自对完整文本的别名扫描（独立于形态消费，
        // 保证“想吃素和生酮”里被想吃形态覆盖的“吃素”照常命中）
        String stripped = NEGATED_SPAN.matcher(text).replaceAll("，");

        LinkedHashSet<String> supported = new LinkedHashSet<>();
        List<Map.Entry<String, String>> ordered = normalizer.slotAliases("foodType").entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .toList();
        // 按文本出现位置从早到晚消费别名（同位重叠时长别名优先），输出顺序跟随用户表达顺序
        String aliasScan = stripped;
        while (true) {
            String bestKey = null;
            String bestValue = null;
            int bestIndex = Integer.MAX_VALUE;
            for (Map.Entry<String, String> alias : ordered) {
                int index = aliasScan.indexOf(alias.getKey());
                if (index >= 0 && index < bestIndex) {
                    bestIndex = index;
                    bestKey = alias.getKey();
                    bestValue = alias.getValue();
                }
            }
            if (bestKey == null) {
                break;
            }
            supported.add(bestValue);
            aliasScan = aliasScan.substring(0, bestIndex) + "，"
                    + aliasScan.substring(bestIndex + bestKey.length());
        }

        // 形态串行消费（标签前缀 → 想吃前缀 → 类型后缀），
        // 已消费区域替换为“，”避免“餐食类型是轻食的东西”这类后续形态重复捕获同一段
        String remaining = stripped;

        LinkedHashSet<String> strongCandidates = new LinkedHashSet<>();
        LinkedHashSet<String> weakCandidates = new LinkedHashSet<>();

        StringBuilder afterPrefix = new StringBuilder();
        Matcher prefix = LABEL_PREFIX.matcher(remaining);
        int consumed = 0;
        while (prefix.find()) {
            String span = CHANGE_WORDS.matcher(prefix.group(1)).replaceAll("");
            if (hasCjk(span)) {
                splitValues(span).forEach(strongCandidates::add);
            }
            afterPrefix.append(remaining, consumed, prefix.start()).append('，');
            consumed = prefix.end();
        }
        afterPrefix.append(remaining, consumed, remaining.length());
        remaining = afterPrefix.toString();

        StringBuilder afterWant = new StringBuilder();
        Matcher want = WANT_PREFIX.matcher(remaining);
        consumed = 0;
        while (want.find()) {
            String span = CHANGE_WORDS.matcher(want.group(1)).replaceAll("");
            if (hasCjk(span)) {
                splitValues(span).forEach(weakCandidates::add);
            }
            afterWant.append(remaining, consumed, want.start()).append('，');
            consumed = want.end();
        }
        afterWant.append(remaining, consumed, remaining.length());
        remaining = afterWant.toString();

        Matcher suffix = LABEL_SUFFIX.matcher(remaining);
        while (suffix.find()) {
            String span = LEADING_FILLERS.matcher(CHANGE_WORDS.matcher(suffix.group(1)).replaceAll("")).replaceAll("");
            if (hasCjk(span)) {
                splitValues(span).forEach(weakCandidates::add);
            }
        }

        // 未支持登记：标签前缀形态最显式，原值一律登记；弱形态先过跨槽位守卫，
        // 避免把餐次/口味等其他槽位的表达误登记为类型（如“想吃早餐”“想吃甜的”）。
        // 受支持候选（词表内原值）不登记，与 cuisine 解析器对称。
        LinkedHashSet<String> unsupported = new LinkedHashSet<>();
        for (String candidate : strongCandidates) {
            String normalized = stripTrailingDe(candidate);
            if (!isPlausibleValue(normalized) || supported.contains(normalized)) {
                continue;
            }
            if (normalizer.canonicalValueOf("foodType", normalized) != null) {
                continue;
            }
            unsupported.add(normalized);
        }
        for (String candidate : weakCandidates) {
            String normalized = stripTrailingDe(candidate);
            if (!isPlausibleValue(normalized) || supported.contains(normalized)) {
                continue;
            }
            if (normalizer.canonicalValueOf("foodType", normalized) != null) {
                continue;
            }
            if (belongsToOtherMealSlot(normalized)) {
                continue;
            }
            unsupported.add(normalized);
        }
        // matched 以最终产出为准：只有受支持值或未支持登记真正出现才算命中，
        // “什么类型”这类被可信度过滤的碎片形态不得拉入简报
        return new FoodTypeParse(!supported.isEmpty() || !unsupported.isEmpty(),
                List.copyOf(supported), List.copyOf(unsupported));
    }

    /** 当前可选餐食类型列表（归一器别名表规范值，唯一事实来源）。 */
    public List<String> supportedFoodTypes() {
        return normalizer.canonicalValues("foodType");
    }

    private String stripTrailingDe(String value) {
        if (value == null) {
            return null;
        }
        String stripped = TRAILING_DE.matcher(value).replaceAll("");
        return stripped.isEmpty() ? value : stripped;
    }

    private boolean belongsToOtherMealSlot(String candidate) {
        return OTHER_MEAL_SLOTS.stream().anyMatch(slot -> normalizer.canonicalValueOf(slot, candidate) != null);
    }

    private List<String> splitValues(String span) {
        List<String> values = new ArrayList<>();
        for (String value : SPLIT.split(span)) {
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    /** 过滤明显不是偏好值的碎片（单字、无中文、疑问词），避免问题句被登记为未支持偏好。 */
    private boolean isPlausibleValue(String value) {
        if (value == null || value.length() < 2 || value.length() > 12) {
            return false;
        }
        if (!hasCjk(value)) {
            return false;
        }
        return !List.of("哪个", "什么", "怎么", "如何", "推荐", "随便", "都行", "哪些").contains(value);
    }

    private boolean hasCjk(String value) {
        return value != null && value.chars().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
