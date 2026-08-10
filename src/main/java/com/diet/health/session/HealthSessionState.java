package com.diet.health.session;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.intent.PreferenceSignal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康会话状态（正交意图的持久化形态）。
 * slots 为跨领域槽位 Map，lastResourceIds 用于 ADJUST 时累积排除，preferenceSignals 记录明示偏好。
 */
public record HealthSessionState(
        String sessionId,
        Long userId,
        HealthPhase phase,
        HealthDomain domain,
        HealthTask task,
        List<String> riskFlags,
        Map<String, List<String>> slots,
        List<Long> lastResourceIds,
        List<PreferenceSignal> preferenceSignals
) {

    public static HealthSessionState fresh(String sessionId, Long userId) {
        return new HealthSessionState(sessionId, userId, HealthPhase.START, null, null, List.of(),
                new LinkedHashMap<>(), List.of(), List.of());
    }

    public HealthSessionState withPhase(HealthPhase newPhase) {
        return new HealthSessionState(sessionId, userId, newPhase, domain, task, riskFlags, slots, lastResourceIds, preferenceSignals);
    }

    public HealthSessionState withIntent(HealthDomain newDomain, HealthTask newTask, List<String> newRiskFlags) {
        return new HealthSessionState(sessionId, userId, phase, newDomain, newTask, newRiskFlags, slots, lastResourceIds, preferenceSignals);
    }

    public HealthSessionState withSlots(Map<String, List<String>> newSlots) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, newSlots, lastResourceIds, preferenceSignals);
    }

    public HealthSessionState withPreferenceSignals(List<PreferenceSignal> newSignals) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots, lastResourceIds, newSignals);
    }

    /** 追加本轮推荐资源 ID，去重并保持顺序。 */
    public HealthSessionState appendLastResourceIds(List<String> newIds) {
        if (newIds == null || newIds.isEmpty()) {
            return this;
        }
        LinkedHashMap<Long, Boolean> merged = new LinkedHashMap<>();
        lastResourceIds.forEach(id -> merged.put(id, Boolean.TRUE));
        newIds.stream()
                .filter(id -> id != null && id.matches("\\d+"))
                .map(Long::parseLong)
                .forEach(id -> merged.put(id, Boolean.TRUE));
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots,
                List.copyOf(merged.keySet()), preferenceSignals);
    }
}
