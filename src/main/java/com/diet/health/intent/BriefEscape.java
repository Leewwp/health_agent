package com.diet.health.intent;

/**
 * 简报续轮逃生口（ADR-0016 续轮判定合同）：
 * RECOMMEND 放行单次推荐；ALTERNATIVE 保持替代推荐/ADJUST 通道并豁免推荐预检；
 * DOMAIN_OR_ROUTINE 交完整意图链（明确切换领域或作息提问）；NONE 表示自由文本进入对应简报处理器。
 */
public enum BriefEscape {
    RECOMMEND,
    ALTERNATIVE,
    DOMAIN_OR_ROUTINE,
    NONE
}
