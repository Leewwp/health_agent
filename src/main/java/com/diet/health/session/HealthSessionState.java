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
 * briefLifecycle 为计划简报会话按侧生命周期（OPEN/PAUSED/GENERATED），
 * recommendationConfirmationKey 为推荐前预检确认指纹（简报补充回路规格 v3.2）。
 * pendingPlanClarify 为计划“新建 vs 修改”澄清的挂起标记（ADR-0018：裸计划词/孤立修改
 * 表达不猜测执行，先澄清，消费后清除）。
 * clarifyEpoch 为澄清挂起时的本地日期（ISO-8601）：澄清续轮继承有时效边界，
 * 跨会话日期的陈旧澄清状态不得被无条件继承（2026-08-31 严格路由规格 RC-3/RC-4）。
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
        MealPlanBrief mealPlanBrief,
        boolean recommendationPreflightPending,
        boolean recommendationConfirmed,
        long recommendationConfirmationVersion,
        Map<String, String> briefLifecycle,
        String recommendationConfirmationKey,
        String pendingPlanClarify,
        String clarifyEpoch
) {

    /** 替代推荐和同一任务连续推荐的有界资源历史，避免会话 JSON 无限增长。 */
    public static final int MAX_RESOURCE_HISTORY = 50;

    public HealthSessionState(String sessionId, Long userId, HealthPhase phase, HealthDomain domain,
                              HealthTask task, List<String> riskFlags, Map<String, List<String>> slots,
                              List<SessionResourceRef> lastResources, List<PreferenceSignal> preferenceSignals,
                              PlanBrief planBrief) {
        this(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources, preferenceSignals,
                planBrief, MealPlanBrief.empty(), false, false, 0, Map.of(), null, null, null);
    }

    /** 兼容旧 14 参构造器：新会话无生命周期与确认指纹。 */
    public HealthSessionState(String sessionId, Long userId, HealthPhase phase, HealthDomain domain,
                              HealthTask task, List<String> riskFlags, Map<String, List<String>> slots,
                              List<SessionResourceRef> lastResources, List<PreferenceSignal> preferenceSignals,
                              PlanBrief planBrief, MealPlanBrief mealPlanBrief,
                              boolean recommendationPreflightPending, boolean recommendationConfirmed,
                              long recommendationConfirmationVersion) {
        this(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources, preferenceSignals,
                planBrief, mealPlanBrief, recommendationPreflightPending, recommendationConfirmed,
                recommendationConfirmationVersion, Map.of(), null, null, null);
    }

    /** 兼容旧 16 参构造器（含生命周期与确认指纹）。 */
    public HealthSessionState(String sessionId, Long userId, HealthPhase phase, HealthDomain domain,
                              HealthTask task, List<String> riskFlags, Map<String, List<String>> slots,
                              List<SessionResourceRef> lastResources, List<PreferenceSignal> preferenceSignals,
                              PlanBrief planBrief, MealPlanBrief mealPlanBrief,
                              boolean recommendationPreflightPending, boolean recommendationConfirmed,
                              long recommendationConfirmationVersion, Map<String, String> briefLifecycle,
                              String recommendationConfirmationKey) {
        this(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources, preferenceSignals,
                planBrief, mealPlanBrief, recommendationPreflightPending, recommendationConfirmed,
                recommendationConfirmationVersion, briefLifecycle, recommendationConfirmationKey, null, null);
    }

    /** 兼容旧 17 参构造器（含澄清挂起标记，无澄清日期戳）。 */
    public HealthSessionState(String sessionId, Long userId, HealthPhase phase, HealthDomain domain,
                              HealthTask task, List<String> riskFlags, Map<String, List<String>> slots,
                              List<SessionResourceRef> lastResources, List<PreferenceSignal> preferenceSignals,
                              PlanBrief planBrief, MealPlanBrief mealPlanBrief,
                              boolean recommendationPreflightPending, boolean recommendationConfirmed,
                              long recommendationConfirmationVersion, Map<String, String> briefLifecycle,
                              String recommendationConfirmationKey, String pendingPlanClarify) {
        this(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources, preferenceSignals,
                planBrief, mealPlanBrief, recommendationPreflightPending, recommendationConfirmed,
                recommendationConfirmationVersion, briefLifecycle, recommendationConfirmationKey,
                pendingPlanClarify, null);
    }

    public static HealthSessionState fresh(String sessionId, Long userId) {
        return new HealthSessionState(sessionId, userId, HealthPhase.START, null, null, List.of(),
                new LinkedHashMap<>(), List.of(), List.of(), PlanBrief.empty());
    }

    public HealthSessionState withPhase(HealthPhase newPhase) {
        return copy(newPhase, domain, task, riskFlags, slots, lastResources, preferenceSignals, planBrief, mealPlanBrief);
    }

    public HealthSessionState withIntent(HealthDomain newDomain, HealthTask newTask, List<String> newRiskFlags) {
        return copy(phase, newDomain, newTask, newRiskFlags, slots, lastResources, preferenceSignals, planBrief, mealPlanBrief);
    }

    public HealthSessionState withSlots(Map<String, List<String>> newSlots) {
        return copy(phase, domain, task, riskFlags, newSlots, lastResources, preferenceSignals, planBrief, mealPlanBrief);
    }

    public HealthSessionState withPreferenceSignals(List<PreferenceSignal> newSignals) {
        return copy(phase, domain, task, riskFlags, slots, lastResources, newSignals, planBrief, mealPlanBrief);
    }

    public HealthSessionState withPlanBrief(PlanBrief newBrief) {
        return copy(phase, domain, task, riskFlags, slots, lastResources, preferenceSignals,
                newBrief == null ? PlanBrief.empty() : newBrief, mealPlanBrief);
    }

    public HealthSessionState withMealPlanBrief(MealPlanBrief newBrief) {
        return copy(phase, domain, task, riskFlags, slots, lastResources, preferenceSignals,
                planBrief, newBrief == null ? MealPlanBrief.empty() : newBrief);
    }

    /** 更新单次推荐前确认状态；该状态只属于当前会话任务，不写入长期偏好。 */
    public HealthSessionState withRecommendationState(boolean pending, boolean confirmed, long version) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources,
                preferenceSignals, planBrief, mealPlanBrief, pending, confirmed, Math.max(0, version),
                briefLifecycle, recommendationConfirmationKey, pendingPlanClarify, clarifyEpoch);
    }

    /** 写入推荐前预检确认指纹（SHA-256 canonical），槽位/领域/资源版本变化会使旧指纹失效。 */
    public HealthSessionState withRecommendationConfirmationKey(String key) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources,
                preferenceSignals, planBrief, mealPlanBrief, recommendationPreflightPending,
                recommendationConfirmed, recommendationConfirmationVersion, briefLifecycle, key,
                pendingPlanClarify, clarifyEpoch);
    }

    /** 整体替换简报生命周期 Map（键为 MEAL/EXERCISE，值为 OPEN/PAUSED/GENERATED）。 */
    public HealthSessionState withBriefLifecycle(Map<String, String> nextLifecycle) {
        Map<String, String> normalized = nextLifecycle == null ? Map.of() : Map.copyOf(nextLifecycle);
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources,
                preferenceSignals, planBrief, mealPlanBrief, recommendationPreflightPending,
                recommendationConfirmed, recommendationConfirmationVersion, normalized,
                recommendationConfirmationKey, pendingPlanClarify, clarifyEpoch);
    }

    /** 写入“新建 vs 修改”澄清挂起标记；null 表示无挂起（ADR-0018 状态策略，不猜测执行）。 */
    public HealthSessionState withPendingPlanClarify(String nextPending) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources,
                preferenceSignals, planBrief, mealPlanBrief, recommendationPreflightPending,
                recommendationConfirmed, recommendationConfirmationVersion, briefLifecycle,
                recommendationConfirmationKey, nextPending, clarifyEpoch);
    }

    /** 写入澄清日期戳（ISO 本地日期）；非澄清轮写 null 表示无时效保护需求。 */
    public HealthSessionState withClarifyEpoch(String nextEpoch) {
        return new HealthSessionState(sessionId, userId, phase, domain, task, riskFlags, slots, lastResources,
                preferenceSignals, planBrief, mealPlanBrief, recommendationPreflightPending,
                recommendationConfirmed, recommendationConfirmationVersion, briefLifecycle,
                recommendationConfirmationKey, pendingPlanClarify, nextEpoch);
    }

    private HealthSessionState copy(HealthPhase nextPhase, HealthDomain nextDomain, HealthTask nextTask,
                                    List<String> nextRiskFlags, Map<String, List<String>> nextSlots,
                                    List<SessionResourceRef> nextResources, List<PreferenceSignal> nextSignals,
                                    PlanBrief nextPlanBrief, MealPlanBrief nextMealBrief) {
        return new HealthSessionState(sessionId, userId, nextPhase, nextDomain, nextTask, nextRiskFlags, nextSlots,
                nextResources, nextSignals, nextPlanBrief, nextMealBrief,
                recommendationPreflightPending, recommendationConfirmed, recommendationConfirmationVersion,
                briefLifecycle, recommendationConfirmationKey, pendingPlanClarify, clarifyEpoch);
    }

    /** 追加本轮类型化资源引用，按 (type, id) 去重并保持顺序。 */
    public HealthSessionState appendLastResources(List<SessionResourceRef> newRefs) {
        if (newRefs == null || newRefs.isEmpty()) {
            return this;
        }
        Set<SessionResourceRef> merged = new LinkedHashSet<>(lastResources == null ? List.of() : lastResources);
        merged.addAll(newRefs);
        List<SessionResourceRef> bounded = new ArrayList<>(merged);
        if (bounded.size() > MAX_RESOURCE_HISTORY) {
            bounded = bounded.subList(bounded.size() - MAX_RESOURCE_HISTORY, bounded.size());
        }
        return copy(phase, domain, task, riskFlags, slots, List.copyOf(bounded), preferenceSignals, planBrief, mealPlanBrief);
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
        if (merged.size() > MAX_RESOURCE_HISTORY) {
            merged = new ArrayList<>(merged.subList(merged.size() - MAX_RESOURCE_HISTORY, merged.size()));
        }
        return copy(phase, domain, task, riskFlags, slots, List.copyOf(merged), preferenceSignals, planBrief, mealPlanBrief);
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
