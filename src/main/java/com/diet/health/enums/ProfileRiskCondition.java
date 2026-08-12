package com.diet.health.enums;

/**
 * 档案结构化风险条件（62 号票，规格 3/8.1/9）：首版 BLOCK_PLAN 所需的长期风险信息，
 * 以结构化枚举保存于健康档案（选填），由 Java 领域规则评估，不依赖 LLM 判断。
 * <p>
 * 条件缺省视为无风险；自由文本说明 {@code riskNote} 仅作补充展示，不参与判定。
 */
public enum ProfileRiskCondition {

    /** 孕产（孕期/哺乳期）。 */
    PREGNANCY,

    /** 当前伤病（活动相关的损伤，未愈）。 */
    CURRENT_INJURY,

    /** 术后/康复期。 */
    POST_SURGERY_REHAB,

    /** 进食障碍。 */
    EATING_DISORDER,

    /** 需要医疗干预的慢性病。 */
    CHRONIC_CONDITION
}
