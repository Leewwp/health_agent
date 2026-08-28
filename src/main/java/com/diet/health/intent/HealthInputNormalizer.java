package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 健康聊天输入归一器：只处理现有槽位词汇的最小别名、领域投影和否定安全，不执行任何 I/O。
 * 模型输出与确定性降级路径必须共用本类，避免两条路径接受不同的用户说法。
 */
@Component
public class HealthInputNormalizer {

    private static final Map<String, List<String>> SLOT_ALIASES = createAliases();
    private static final Set<String> NEGATION_WORDS = Set.of("不要", "不用", "不想", "别", "避免", "排除", "不练", "没", "没有");

    /** 归一用户原文和上游槽位，并只保留当前领域允许的槽位。 */
    public NormalizationResult normalize(HealthDomain domain, String userInput,
                                         Map<String, List<String>> rawSlots) {
        Map<String, LinkedHashSet<String>> collected = new LinkedHashMap<>();
        Set<String> negatedSlots = new LinkedHashSet<>();
        boolean unsafe = false;
        String text = effectiveRequestText(userInput);

        if (rawSlots != null) {
            for (Map.Entry<String, List<String>> entry : rawSlots.entrySet()) {
                if (!slotsFor(domain).contains(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                for (String rawValue : entry.getValue()) {
                    String canonical = canonicalValue(entry.getKey(), rawValue);
                    if (canonical == null) {
                        continue;
                    }
                    if (isNegated(text, entry.getKey(), canonical)) {
                        unsafe = true;
                        negatedSlots.add(entry.getKey() + ":" + canonical);
                        continue;
                    }
                    collected.computeIfAbsent(entry.getKey(), key -> new LinkedHashSet<>()).add(canonical);
                }
            }
        }

        for (String slot : slotsFor(domain)) {
            List<int[]> matchedRanges = new ArrayList<>();
            List<Map.Entry<String, String>> aliases = aliasesFor(slot).entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed())
                    .toList();
            for (Map.Entry<String, String> alias : aliases) {
                List<int[]> ranges = occurrences(text, alias.getKey());
                // “器械：哑铃”中的“器械”是字段标签，不是用户选择的器材值。
                if ("equipment".equals(slot) && "器械".equals(alias.getKey())) {
                    ranges = ranges.stream().filter(range -> !isFieldLabelOccurrence(text, range)).toList();
                }
                if (ranges.isEmpty() || ranges.stream().noneMatch(range -> isUncovered(range, matchedRanges))) {
                    continue;
                }
                matchedRanges.addAll(ranges.stream().filter(range -> isUncovered(range, matchedRanges)).toList());
                if (isBodyweightPhrase(alias.getKey())) {
                    collected.computeIfAbsent("equipment", key -> new LinkedHashSet<>()).add("徒手");
                } else if ("equipment".equals(slot) && "器械".equals(alias.getKey()) && containsBodyweightPhrase(text)) {
                    continue;
                } else if (isNegatedOccurrence(text, alias.getKey())) {
                    unsafe = true;
                    negatedSlots.add(slot + ":" + alias.getValue());
                } else {
                    collected.computeIfAbsent(slot, key -> new LinkedHashSet<>()).add(alias.getValue());
                }
            }
        }

        Map<String, List<String>> normalized = new LinkedHashMap<>();
        collected.forEach((slot, values) -> {
            if (!values.isEmpty()) {
                normalized.put(slot, List.copyOf(values));
            }
        });
        return new NormalizationResult(Map.copyOf(normalized), unsafe, List.copyOf(negatedSlots));
    }

    private List<int[]> occurrences(String text, String alias) {
        List<int[]> ranges = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf(alias, from);
            if (index < 0) break;
            ranges.add(new int[]{index, index + alias.length()});
            from = index + alias.length();
        }
        return ranges;
    }

    private boolean isFieldLabelOccurrence(String text, int[] range) {
        int index = range[1];
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index < text.length() && (text.charAt(index) == ':' || text.charAt(index) == '：');
    }

    private boolean isUncovered(int[] range, List<int[]> matchedRanges) {
        return matchedRanges.stream().noneMatch(existing -> range[0] < existing[1] && existing[0] < range[1]);
    }

    /** 当前领域允许的槽位集合。 */
    public List<String> slotsFor(HealthDomain domain) {
        if (domain == null) {
            return List.of();
        }
        return switch (domain) {
            case MEAL -> HealthSlotDictionary.MEAL_SLOTS;
            case EXERCISE -> HealthSlotDictionary.FITNESS_SLOTS;
            case ROUTINE -> HealthSlotDictionary.ROUTINE_SLOTS;
            case COMPOSITE, OTHER -> List.of();
        };
    }

    /** 只投影当前领域槽位，不改变持久化的历史槽位。 */
    public Map<String, List<String>> project(HealthDomain domain, Map<String, List<String>> slots) {
        Map<String, List<String>> projected = new LinkedHashMap<>();
        if (slots == null) {
            return projected;
        }
        for (String slot : slotsFor(domain)) {
            List<String> values = slots.get(slot);
            if (values != null && !values.isEmpty()) {
                projected.put(slot, List.copyOf(values));
            }
        }
        return projected;
    }

    private String canonicalValue(String slot, String value) {
        String normalized = compact(value);
        if (normalized.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> alias : aliasesFor(slot).entrySet()) {
            if (normalized.equals(alias.getKey()) || normalized.equals(alias.getValue())) {
                return alias.getValue();
            }
        }
        return normalized;
    }

    private boolean isNegated(String text, String slot, String canonical) {
        if (text.isEmpty()) {
            return false;
        }
        return aliasesFor(slot).entrySet().stream()
                .filter(entry -> canonical.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .anyMatch(alias -> text.contains(alias) && isNegatedOccurrence(text, alias));
    }

    private boolean isNegatedOccurrence(String text, String alias) {
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf(alias, from);
            if (index < 0) {
                return false;
            }
            String prefix = text.substring(Math.max(0, index - 4), index);
            if (NEGATION_WORDS.stream().anyMatch(prefix::contains) || prefix.endsWith("不")) {
                return true;
            }
            from = index + alias.length();
        }
        return false;
    }

    /** 只保留当前请求：昨天/之前的事实不能把历史餐次写进本轮槽位。 */
    private String effectiveRequestText(String userInput) {
        String text = compact(userInput);
        if (text.isEmpty()) {
            return text;
        }
        return text.replaceFirst("^(昨天|前天|之前|上周)[^，。！？!?]*[，。！？!?]", "");
    }

    private boolean isBodyweightPhrase(String alias) {
        return "无器械".equals(alias) || "不用器械".equals(alias) || "不使用器械".equals(alias);
    }

    private boolean containsBodyweightPhrase(String text) {
        return text.contains("无器械") || text.contains("不用器械") || text.contains("不使用器械");
    }

    private Map<String, String> aliasesFor(String slot) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : SLOT_ALIASES.getOrDefault(slot, List.of())) {
            String[] parts = entry.split("=", 2);
            result.put(parts[0], parts[1]);
        }
        return result;
    }

    private String compact(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }

    private static Map<String, List<String>> createAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("bodyParts", List.of(
                // “臀腿”在动作口语中作为复合部位沿用 benchmark 的“腿”标注；明确并列的“臀和腿”仍会分别提取。
                "臀腿=腿",
                "胸大肌=胸", "胸肌=胸", "胸部=胸", "胸=胸",
                "背部=背", "背肌=背", "背=背",
                "大腿=腿", "小腿=腿", "腿部=腿", "腿=腿",
                "肩部=肩", "肩膀=肩", "肩=肩",
                "脖子=颈部", "颈部=颈部",
                "手臂=手臂", "胳膊=手臂",
                "腰腹=核心", "腹部=核心", "腹肌=核心", "核心=核心",
                "臀大肌=臀", "臀肌=臀", "臀部=臀", "臀=臀", "全身=全身"));
        aliases.put("difficulty", List.of("初学者=入门", "新手=入门", "轻量=入门", "入门=入门", "进阶=进阶", "挑战=挑战"));
        aliases.put("trainingGoal", List.of("减肥=减脂", "瘦身=减脂", "减脂=减脂", "增肌=增肌", "耐力=耐力",
                "力量=力量", "柔韧=柔韧", "保持健康=保持健康"));
        aliases.put("equipment", List.of("不使用器械=徒手", "不用器械=徒手", "无器械=徒手", "自重=徒手", "徒手=徒手",
                "哑铃=哑铃", "杠铃=杠铃", "弹力带=弹力带", "壶铃=壶铃", "器械=器械"));
        aliases.put("mealTime", List.of("早餐=早餐", "早饭=早餐", "午餐=午餐", "午饭=午餐", "中饭=午餐", "中午=午餐",
                "晚餐=晚餐", "晚饭=晚餐", "今晚=晚餐", "晚上=晚餐", "加餐=加餐"));
        aliases.put("mood", List.of("没胃口=没胃口", "胃口不好=没胃口", "没有胃口=没胃口", "没食欲=没胃口",
                "疲惫=疲惫", "烦躁=烦躁", "开心=开心", "焦虑=焦虑", "低落=低落", "平静=平静", "压力大=压力大",
                "想放松=想放松", "想奖励自己=想奖励自己"));
        aliases.put("scene", List.of("工作=工作", "上班=工作", "办公=工作", "校园=校园", "家里=家里", "周末=周末",
                "加班=加班", "运动后=运动后", "通勤=通勤", "聚餐=聚餐", "独处=独处", "旅行=旅行"));
        aliases.put("healthGoal", List.of("清淡点=清淡", "清淡一点=清淡", "清淡口味=清淡", "清淡=清淡", "减肥=减脂",
                "减重=减脂", "瘦身=减脂", "减脂=减脂", "高蛋白=高蛋白", "养胃=养胃", "均衡=均衡", "低油=低油",
                "低盐=低盐", "低糖=低糖", "补能=补能", "增肌=增肌", "控碳水=控碳水", "易消化=易消化", "暖胃=暖胃"));
        aliases.put("cuisine", List.of("想吃素=素食", "吃素=素食", "纯素=素食", "素食=素食", "川菜=川菜", "粤菜=粤菜",
                "湘菜=湘菜", "江浙菜=江浙菜", "东北菜=东北菜", "鲁菜=鲁菜", "闽南菜=闽南菜", "云南菜=云南菜",
                "新疆菜=新疆菜", "轻食=轻食", "西餐=西餐", "日料=日料", "韩餐=韩餐", "东南亚菜=东南亚菜", "火锅=火锅",
                "烧烤=烧烤", "海鲜=海鲜", "家常菜=家常", "家常=家常", "小吃=小吃", "粉面=粉面", "粥汤=粥汤", "快餐=快餐", "甜品=甜品"));
        aliases.put("taste", List.of("微辣=微辣", "中辣=中辣", "麻辣=麻辣", "辣=辣",
                "酸甜口味=酸甜", "酸甜口=酸甜", "酸甜=酸甜", "甜口=甜", "甜=甜", "咸鲜=咸鲜", "鲜香=鲜香", "酱香=酱香",
                "蒜香=蒜香", "番茄味=番茄味", "咖喱味=咖喱味", "奶香=奶香", "油香=油香", "烟火气=烟火气"));
        aliases.put("convenience", List.of("尽快能吃上=快速", "尽快吃上=快速", "马上能吃=快速", "赶时间=快速", "快速=快速",
                "快点=快速", "快的=快速", "便利店=快速", "速食=快速", "外带=外带方便", "外卖带走=外带方便", "外带方便=外带方便",
                "慢享=慢享", "堂食舒服=堂食舒服", "少排队=少排队", "少餐具=少餐具", "一人食=一人食", "多人共享=多人共享",
                "适合备餐=适合备餐", "适合边走边吃=适合边走边吃"));
        return Collections.unmodifiableMap(aliases);
    }

    public record NormalizationResult(Map<String, List<String>> slots, boolean requiresClarification,
                                      List<String> negatedSlots) {
        public NormalizationResult(Map<String, List<String>> slots, boolean requiresClarification) {
            this(slots, requiresClarification, List.of());
        }
    }
}
