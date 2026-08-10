package com.diet.health.plan;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * LLM 输出后 Guard（34 号，规格 9）：
 * 解释文本不得携带自主营养数值（kcal 声明）、医疗结论词或绝对化用语。
 * 候选 ID 白名单由 AgentContractModule 负责，本 Guard 负责文本级校验；
 * 命中任一规则立即模板降级，LLM 不拥有最终解释权。
 */
public final class PlanOutputGuard {

    /** 自主营养数值声明：任意数字 + kcal/千卡/大卡。 */
    private static final Pattern KCAL_CLAIM = Pattern.compile("\\d+\\s*(kcal|千卡|大卡)");

    /** 医疗结论词。 */
    private static final java.util.List<String> MEDICAL_WORDS = java.util.List.of("治疗", "诊断", "处方", "治愈", "用药");

    /** 绝对化用语。 */
    private static final Pattern ABSOLUTE_WORDS = Pattern.compile("(最好|保证|绝对|最有效)");

    private PlanOutputGuard() {
    }

    /** 校验解释文本，返回失败原因；无问题时返回 empty。 */
    public static Optional<String> validate(String speechText) {
        if (speechText == null || speechText.isBlank()) {
            return Optional.of("speechText 为空");
        }
        if (KCAL_CLAIM.matcher(speechText).find()) {
            return Optional.of("LLM 输出包含自主营养数值（kcal 声明）");
        }
        for (String word : MEDICAL_WORDS) {
            if (speechText.contains(word)) {
                return Optional.of("LLM 输出包含医疗结论词：" + word);
            }
        }
        if (ABSOLUTE_WORDS.matcher(speechText).find()) {
            return Optional.of("LLM 输出包含绝对化用语");
        }
        return Optional.empty();
    }
}
