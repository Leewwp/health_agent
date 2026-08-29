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
 * 确定性受限菜系意图解析器（简报补充回路规格 v3.2）。
 * <p>
 * 不依赖模型、不参与领域/任务路由，输出只写 {@link MealPlanBrief}。输入范围仅两种形态：
 * ① 显式标签形态：“菜系：X / 菜系是 X / X 菜系”；② 封闭超类词表（中餐、中式等极小封闭清单，
 * 明确为“非库标签”，可在自然句中出现）。解析器先剥除否定范围（“不喜欢/不要/避免 X”
 * 不得写入正向偏好），再按中文标点和“和/或/以及”分隔候选值；解析出的值先过归一器别名表：
 * 命中 → 受支持菜系（可过滤）；未命中但在两种形态内 → 原值登记未支持集合；
 * 两种形态之外 → 不解析（matched=false），由简报服务走 INVALID 枚举指引。
 * 模型 rawSlots 中未被别名表收录的未知值必须丢弃，只有本解析器能产生未支持原值。
 */
@Component
public class MealCuisineIntentParser {

    /** 显式标签前缀形态：菜系：X / 菜系是 X / 菜系 X。 */
    private static final Pattern LABEL_PREFIX = Pattern.compile("菜系\\s*[:：是]?\\s*([^，。；,;！!？?\\n]{1,24})");

    /** 显式标签后缀形态：X 菜系（如“粤菜菜系”“中式菜系”）。 */
    private static final Pattern LABEL_SUFFIX = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]{1,8})菜系");

    /** 封闭超类词表（非库标签，明确不进入别名表）。 */
    private static final List<String> SUPERCLASS_WORDS = List.of("中餐", "中式");

    /** 否定范围：否定词 + 短语（不含分隔标点），整体剥除不得写入正向偏好。 */
    private static final Pattern NEGATED_SPAN =
            Pattern.compile("(?:不喜欢|不想吃|不想要|不要|不吃|不用|不碰|避免|排除|别)\\s*的?\\s*([^，。；,;！!？?\\n]{1,12})");

    /** 候选值分隔：中文标点 + “和/或/以及”。 */
    private static final Pattern SPLIT = Pattern.compile("[、，,；;和或以及\\s]+");

    private final HealthInputNormalizer normalizer;

    public MealCuisineIntentParser(HealthInputNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /** 解析结果：matched 是否命中两种形态之一；supported 受支持规范值；unsupported 形态内未收录原值。 */
    public record CuisineParse(boolean matched, List<String> supported, List<String> unsupported) {

        public static CuisineParse notMatched() {
            return new CuisineParse(false, List.of(), List.of());
        }
    }

    public CuisineParse parse(String input) {
        String text = input == null ? "" : input.replaceAll("\\s+", "").trim();
        if (text.isEmpty()) {
            return CuisineParse.notMatched();
        }
        // 先剥除否定范围，再识别两种形态
        String stripped = NEGATED_SPAN.matcher(text).replaceAll("，");

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        boolean matched = false;

        Matcher prefix = LABEL_PREFIX.matcher(stripped);
        while (prefix.find()) {
            String span = prefix.group(1);
            if (hasCjk(span)) {
                matched = true;
                splitValues(span).forEach(value -> {
                    if (isPlausibleValue(value)) candidates.add(value);
                });
            }
        }
        Matcher suffix = LABEL_SUFFIX.matcher(stripped);
        while (suffix.find()) {
            String value = suffix.group(1);
            if (isPlausibleValue(value)) {
                matched = true;
                candidates.add(value);
            }
        }
        for (String superclass : SUPERCLASS_WORDS) {
            if (stripped.contains(superclass)) {
                matched = true;
                candidates.add(superclass);
            }
        }

        // 形态命中或命中受支持菜系别名时整句扫描菜系别名（substring 口径与归一器一致，
        // 长别名优先避免子串误伤）：“中餐、川菜”这类混合表达里受支持的值同样被采用；
        // 受支持集合的唯一事实来源是归一器别名表，别名命中本身即属于确定性形态内表达
        LinkedHashSet<String> supported = new LinkedHashSet<>();
        LinkedHashSet<String> unsupported = new LinkedHashSet<>();
        Map<String, String> aliases = normalizer.slotAliases("cuisine");
        List<Map.Entry<String, String>> ordered = aliases.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .toList();
        String remaining = stripped;
        for (Map.Entry<String, String> alias : ordered) {
            if (remaining.contains(alias.getKey())) {
                matched = true;
                supported.add(alias.getValue());
                remaining = remaining.replace(alias.getKey(), "，");
            }
        }
        if (!matched) {
            return CuisineParse.notMatched();
        }
        for (String candidate : candidates) {
            String canonical = normalizer.canonicalValueOf("cuisine", candidate);
            if (canonical != null && supported.contains(canonical)) {
                continue;
            }
            // 未命中别名表的形态内原值：登记未支持（如“中餐”）
            unsupported.add(candidate);
        }
        return new CuisineParse(true, List.copyOf(supported), List.copyOf(unsupported));
    }

    /** 当前可选菜系列表（归一器别名表规范值，唯一事实来源）。 */
    public List<String> supportedCuisines() {
        return normalizer.canonicalValues("cuisine");
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
