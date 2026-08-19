package com.diet.health.session;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.intent.PreferenceSignal;
import com.diet.health.plan.PlanBrief;
import com.diet.health.plan.MealPlanBrief;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 健康会话状态（正交意图的持久化形态）。
 * slots 为跨领域槽位 Map，lastResources 为类型化资源引用（43 号票：ADJUST 排除只取
 * MEAL/EXERCISE，作息事实保留在历史中但不参与排除），preferenceSignals 记录明示偏好。
 */
public record HealthSessionState(
        String sessionId,
        Long userId,
        HealthPhase phase,
        HealthDomain domain,
        HealthTask task,
        List<String> riskFlags,
        Map<String, List<String>> slots,
        List<SessionResourceRef> lastResources,
        List<PreferenceSignal> preferenceSignals,
        PlanBrief planBrief,
        MealPlanBrief mealPlanBrief
) {

    public HealthSessionState(String sessionId, Long userId, HealthPhase phase, HealthDomain domain,
                              HealthTask task, List<String> riskFlags, Map<String, List<String>> slots,
                              List<SessionResourceRef> lastResources, List<PreferenceSignal> preferenceSignals,
                              PlanBrief planBrief) {
        this(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources, preferenceSignals,
                planBrief, MealPlanBrief.empty());
    }

    public static HealthSessionState fresh(String sessionId, Long userId) {
        return new HealthSessionState(sessionId, userId, HealthPhase.START, null, null, List.of(),
                new LinkedHashMap<>(), List.of(), List.of(), PlanBrief.empty());
    }

    public HealthSessionState withPhase(HealthPhase newPhase) {
        return new HealthSessionState(sessionId, userId, newPhase, domain, task, riskFlags, slots, lastResources, preferenceSignals, planBrief, mealPlanBrief);
    }

    public HealthSessionState withIntent(HealthDomain newDomain, HealthTask newTask, List<String> newRiskFlags) {
        return new HealthSessionState(sessionId, userId, phase, newDomain, newTask, newRiskFlags, slots, lastResources, preferenceSignals, planBrief, mealPlanBrief);
    }

    public HealthSessionState withSlots(Map<String, List<String>> newSlots) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, newSlots, lastResources, preferenceSignals, planBrief, mealPlanBrief);
    }

    public HealthSessionState withPreferenceSignals(List<PreferenceSignal> newSignals) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources, newSignals, planBrief, mealPlanBrief);
    }

    public HealthSessionState withPlanBrief(PlanBrief newBrief) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources,
                preferenceSignals, newBrief == null ? PlanBrief.empty() : newBrief, mealPlanBrief);
    }

    public HealthSessionState withMealPlanBrief(MealPlanBrief newBrief) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources,
                preferenceSignals, planBrief, newBrief == null ? MealPlanBrief.empty() : newBrief);
    }

    /** 追加本轮类型化资源引用，按 (type, id) 去重并保持顺序。 */
    public HealthSessionState appendLastResources(List<SessionResourceRef> newRefs) {
        if (newRefs == null || newRefs.isEmpty()) {
            return this;
        }
        Set<SessionResourceRef> merged = new LinkedHashSet<>(lastResources);
        merged.addAll(newRefs);
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots,
                List.copyOf(merged), preferenceSignals, planBrief, mealPlanBrief);
    }

    /**
     * 用本轮结果替换同类型的上一轮结果，其他类型保留用于兼容跨领域历史。
     * ADJUST 因此只排除最近一轮同领域资源，不会无限累积整段会话。
     */
    public HealthSessionState replaceLastResources(List<SessionResourceRef> newRefs) {
        if (newRefs == null || newRefs.isEmpty()) {
            return this;
        }
        Set<String> replacedTypes = newRefs.stream()
                .map(SessionResourceRef::type)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<SessionResourceRef> merged = new ArrayList<>();
        for (SessionResourceRef ref : lastResources) {
            if (ref.type() == null || !replacedTypes.contains(ref.type())) {
                merged.add(ref);
            }
        }
        newRefs.stream().distinct().forEach(merged::add);
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots,
                List.copyOf(merged), preferenceSignals, planBrief, mealPlanBrief);
    }

    /**
     * 排除引用提取（43 号票 + #69 类型化契约）：只取指定类型（MEAL/EXERCISE）的
     * 类型化字符串 resourceId；type 为 null 的旧版遗留引用按双类型兼容排除；
     * ROUTINE 不参与。fixture 种子 ID（M1-M9/9001 等）原样返回，不做数值强转；
     * reviewed 库路径由调用方在进入数据库查询前解析数值 ID（非法/跨模式 ID 显式忽略）。
     */
    public List<String> excludeIdsFor(String type) {
        List<String> ids = new ArrayList<>();
        for (SessionResourceRef ref : lastResources) {
            boolean typed = type.equals(ref.type());
            boolean legacy = ref.type() == null;
            if ((typed || legacy) && ref.id() != null && !ref.id().isBlank()) {
                ids.add(ref.id());
            }
        }
        return List.copyOf(ids);
    }
}
