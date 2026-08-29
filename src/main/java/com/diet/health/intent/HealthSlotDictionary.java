package com.diet.health.intent;

import com.diet.service.slot.SlotOptionService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 健康槽位字典（版本化）：
 * 饮食槽位沿用 {@code diet_slot_option} 字典；健身槽位使用版本化种子值；作息时间/时长使用结构化格式。
 */
@Component
public class HealthSlotDictionary {

    /** 健身槽位种子版本，调整取值时必须同步提升版本并更新 Prompt。 */
    public static final String FITNESS_SLOT_VERSION = "2026-08-20-v2";

    public static final List<String> MEAL_SLOTS = List.of("mealTime", "mood", "scene", "healthGoal", "cuisine", "foodType", "taste", "convenience");
    public static final List<String> FITNESS_SLOTS = List.of("bodyParts", "equipment", "trainingGoal", "difficulty");
    public static final List<String> ROUTINE_SLOTS = List.of("wakeTime", "bedtime", "sleepDuration");

    /** 健身槽位合法值（版本化种子）。 */
    public static final Map<String, List<String>> FITNESS_OPTIONS = Map.of(
            "bodyParts", List.of("胸", "背", "腿", "肩", "手臂", "核心", "臀", "颈部", "全身"),
            "equipment", List.of("徒手", "哑铃", "杠铃", "弹力带", "壶铃", "器械"),
            "trainingGoal", List.of("增肌", "减脂", "耐力", "力量", "柔韧", "保持健康"),
            "difficulty", List.of("入门", "进阶", "挑战")
    );

    /** 作息结构化槽位格式：HH:mm。 */
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** 作息结构化槽位格式：数字小时。 */
    private static final Pattern HOUR_PATTERN = Pattern.compile("^\\d{1,2}(\\.\\d)?$");

    private final SlotOptionService slotOptionService;

    public HealthSlotDictionary(SlotOptionService slotOptionService) {
        this.slotOptionService = slotOptionService;
    }

    /** 全部合法取值：饮食来自 DB 字典，健身/作息来自种子。 */
    public Map<String, List<String>> legalValues() {
        Map<String, List<String>> result = new LinkedHashMap<>(slotOptionService.findAllOptions());
        FITNESS_OPTIONS.forEach(result::put);
        return result;
    }

    /** 是否为自由格式槽位（时间/时长），不参与枚举校验。 */
    public boolean isStructuredSlot(String slotName) {
        return "wakeTime".equals(slotName) || "bedtime".equals(slotName) || "sleepDuration".equals(slotName);
    }

    /** 判断某个槽位值是否合法：枚举槽位查字典，结构化槽位查格式。 */
    public boolean isValid(String slotName, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (isStructuredSlot(slotName)) {
            return "sleepDuration".equals(slotName) ? HOUR_PATTERN.matcher(value).matches() : TIME_PATTERN.matcher(value).matches();
        }
        List<String> allowed = legalValues().get(slotName);
        return allowed != null && allowed.contains(value);
    }

    /** 是否为已知槽位名（任一领域）。 */
    public boolean isKnownSlot(String slotName) {
        return MEAL_SLOTS.contains(slotName) || FITNESS_SLOTS.contains(slotName) || ROUTINE_SLOTS.contains(slotName);
    }

    /** 判断槽位是否属于指定领域。 */
    public boolean belongsTo(String slotName, com.diet.health.enums.HealthDomain domain) {
        if (domain == null) {
            return false;
        }
        return switch (domain) {
            case MEAL -> MEAL_SLOTS.contains(slotName);
            case EXERCISE -> FITNESS_SLOTS.contains(slotName);
            case ROUTINE -> ROUTINE_SLOTS.contains(slotName);
            case COMPOSITE, OTHER -> false;
        };
    }

    /** 过滤非法槽位值，返回净化后的槽位 Map（丢弃不合法条目）。 */
    public Map<String, List<String>> filterLegal(Map<String, List<String>> raw) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            if (!isKnownSlot(entry.getKey())) {
                continue;
            }
            List<String> cleaned = entry.getValue() == null ? List.of()
                    : entry.getValue().stream()
                    .filter(value -> isValid(entry.getKey(), value))
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (!cleaned.isEmpty()) {
                result.put(entry.getKey(), cleaned);
            }
        }
        return result;
    }
}
