package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthTask;

import java.util.List;
import java.util.Map;

/**
 * 正交意图识别结果：domain/task/riskFlags/slots/preferenceSignals。
 * <p>
 * riskFlags 只是待 Java 规则确认的风险信号；最终风险等级与计划拒绝权只属于 Java。
 * degraded=true 表示意图层已经降级（LLM 失败或输出被过滤），调用方应降低置信度但可继续流程。
 */
public record HealthIntentResult(
        HealthDomain domain,
        HealthTask task,
        List<String> riskFlags,
        Map<String, List<String>> slots,
        List<PreferenceSignal> preferenceSignals,
        double confidence,
        boolean degraded,
        String fallbackReason
) {

    public static HealthIntentResult parsed(HealthDomain domain, HealthTask task, List<String> riskFlags,
                                            Map<String, List<String>> slots, List<PreferenceSignal> preferenceSignals,
                                            double confidence) {
        return new HealthIntentResult(domain, task, riskFlags, slots, preferenceSignals, confidence, false, null);
    }

    public static HealthIntentResult degraded(HealthDomain domain, HealthTask task, List<String> riskFlags,
                                              Map<String, List<String>> slots, List<PreferenceSignal> preferenceSignals,
                                              String fallbackReason) {
        return new HealthIntentResult(domain, task, riskFlags, slots, preferenceSignals, 0.2, true, fallbackReason);
    }
}
