package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.session.HealthSessionState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同域推荐换主题的槽位替换/reset 语义（演示召回规格 P1，单一判定源）。
 * <p>
 * 只作用于“同一餐食/健身领域内的推荐请求轮”（跨域切换走既有投影与简报暂停/恢复，
 * 计划简报与综合计划共享目标继承不受影响）：
 * <ul>
 *   <li>活动澄清链（上一轮 phase=CLARIFY）与待确认预检（recommendationPreflightPending）内
 *       的输入视为澄清短答，继续既有合并语义，不做任何重置；</li>
 *   <li>显式清除表达（“重置条件/清空条件/清除条件/重新开始/全部清空/不要之前的条件/不要旧条件”）
 *       → 只保留本轮槽位；</li>
 *   <li>“只看/只要/仅看 + 餐次” → 历史只保留 mealTime 维度；</li>
 *   <li>餐食领域无显式表达的新推荐请求且本轮带餐次 → 历史只保留 mealTime
 *       （“清淡晚餐”→“中午吃什么”换主题不被旧偏好悄悄卡住）；</li>
 *   <li>“换成/改为/改成/调整为/修改为”由既有逐槽位覆盖合并承担（槽位层与简报层的
 *       单值/列表重建语义一致），本策略不重复实现。</li>
 * </ul>
 */
public final class RecommendationTopicPolicy {

    /** 显式清除表达：清空/重置历史推荐条件。 */
    private static final List<String> CLEAR_EXPRESSIONS = List.of(
            "重置条件", "清空条件", "清除条件", "重新开始", "全部清空", "不要之前的条件", "不要旧条件");

    /** 只看/只要/仅看：把主题限定到指定维度。 */
    private static final List<String> LOOK_ONLY_WORDS = List.of("只看", "只要", "仅看");

    /** 显式修改词：逐槽位替换（与 MealPlanBriefService 的“换成/改为”重建语义一致）。 */
    private static final List<String> CHANGE_WORDS = List.of("换成", "改为", "改成", "调整为", "修改为");

    /** 餐次值（含口语形式）；“三餐”标签兼容由 SQL/领域过滤承担。 */
    private static final List<String> MEAL_TIME_VALUES = List.of("早餐", "午餐", "晚餐", "三餐", "早饭", "午饭", "晚饭");

    private RecommendationTopicPolicy() {
    }

    /** 策略适用范围：同域（餐食/健身）推荐请求轮。 */
    public static boolean applies(HealthDomain stateDomain, HealthDomain intentDomain, HealthTask intentTask) {
        return stateDomain != null && stateDomain == intentDomain
                && intentTask == HealthTask.RECOMMEND
                && (stateDomain == HealthDomain.MEAL || stateDomain == HealthDomain.EXERCISE);
    }

    /** 裁决原因（Trace 与测试断言用）。 */
    public enum Reason {
        /** 活动澄清链或待确认预检中的短答：继续合并，不做重置。 */
        CLARIFY_INHERIT,
        /** 显式清除表达：只保留本轮槽位。 */
        CLEAR_RESET,
        /** “只看/只要/仅看 + 餐次”：历史只保留 mealTime。 */
        LOOK_ONLY_MEAL_TIME,
        /** 餐食新推荐请求带餐次且无显式表达：历史只保留 mealTime。 */
        MEAL_TOPIC_RESET,
        /** 普通合并（无替换/重置语义）。 */
        PLAIN_MERGE
    }

    /** 裁决结果：采用的槽位与原因。 */
    public record Decision(Map<String, List<String>> slots, Reason reason) {

        /** 澄清短答直接继承合并结果。 */
        public static Decision inherit(Map<String, List<String>> slots) {
            return new Decision(slots, Reason.CLARIFY_INHERIT);
        }
    }

    /**
     * 对“逐槽位覆盖合并”结果应用换主题语义，返回本轮采用的槽位与原因。
     *
     * @param state     会话状态（phase/preflightPending 决定是否澄清短答）
     * @param current   本轮意图槽位
     * @param merged    既有逐槽位覆盖合并结果（history ∪ current）
     * @param userInput 本轮原始输入
     */
    public static Decision decide(HealthSessionState state, Map<String, List<String>> current,
                                  Map<String, List<String>> merged, String userInput) {
        Map<String, List<String>> safeCurrent = current == null ? Map.of() : current;
        Map<String, List<String>> safeMerged = merged == null ? Map.of() : merged;
        if (state.phase() == HealthPhase.CLARIFY || state.recommendationPreflightPending()) {
            return Decision.inherit(safeMerged);
        }
        String text = userInput == null ? "" : userInput.replaceAll("\\s+", "");
        if (containsAny(text, CLEAR_EXPRESSIONS)) {
            return new Decision(copyOf(safeCurrent), Reason.CLEAR_RESET);
        }
        if (lookOnlyMealTime(text)) {
            return new Decision(keepOnly(safeMerged, safeCurrent, List.of("mealTime")), Reason.LOOK_ONLY_MEAL_TIME);
        }
        // “换成/改为”是显式逐槽位修改：仍走普通合并（同名槽位覆盖、其余维度保留），
        // 不得按新主题重置其他历史条件。
        if (state.domain() == HealthDomain.MEAL && !safeCurrent.getOrDefault("mealTime", List.of()).isEmpty()
                && !containsAny(text, CHANGE_WORDS)) {
            return new Decision(keepOnly(safeMerged, safeCurrent, List.of("mealTime")), Reason.MEAL_TOPIC_RESET);
        }
        return new Decision(safeMerged, Reason.PLAIN_MERGE);
    }

    /** 历史只保留 keys 维度，其余采用本轮槽位。 */
    private static Map<String, List<String>> keepOnly(Map<String, List<String>> merged,
                                                      Map<String, List<String>> current,
                                                      List<String> keys) {
        Map<String, List<String>> result = copyOf(current);
        for (String key : keys) {
            result.putIfAbsent(key, merged.getOrDefault(key, List.of()));
        }
        return result;
    }

    private static Map<String, List<String>> copyOf(Map<String, List<String>> slots) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        slots.forEach((key, values) -> result.put(key, List.copyOf(values == null ? List.of() : values)));
        return result;
    }

    private static boolean lookOnlyMealTime(String text) {
        return containsAny(text, LOOK_ONLY_WORDS) && MEAL_TIME_VALUES.stream().anyMatch(text::contains);
    }

    private static boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
    }
}