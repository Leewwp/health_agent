package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.session.HealthSessionState;
import com.diet.health.plan.PlanBrief;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同域推荐换主题策略测试（演示召回规格 P1）：澄清短答继续合并；
 * 显式清除/只看餐次/新推荐带餐次替换旧条件；健身域不触发餐次重置。
 */
class RecommendationTopicPolicyTest {

    private static final Map<String, List<String>> HISTORY = Map.of(
            "mealTime", List.of("晚餐"), "healthGoal", List.of("清淡"));

    private HealthSessionState state(HealthDomain domain, HealthPhase phase, boolean preflightPending) {
        return new HealthSessionState("s1", 1L, phase, domain, HealthTask.RECOMMEND,
                List.of(), Map.of(), List.of(), List.of(), PlanBrief.empty(), null,
                preflightPending, false, 0, Map.of(), null);
    }

    @Test
    void 活动澄清链中的短答继续合并不重置() {
        HealthSessionState state = state(HealthDomain.MEAL, HealthPhase.CLARIFY, false);
        RecommendationTopicPolicy.Decision decision = RecommendationTopicPolicy.decide(state,
                Map.of("mealTime", List.of("午餐")), Map.of("mealTime", List.of("午餐"), "healthGoal", List.of("清淡")),
                "午餐");
        assertEquals(RecommendationTopicPolicy.Reason.CLARIFY_INHERIT, decision.reason());
        assertEquals(List.of("清淡"), decision.slots().get("healthGoal"), "澄清短答必须继续继承历史偏好");
    }

    @Test
    void 待确认预检中的输入按短答继承不重置() {
        HealthSessionState state = state(HealthDomain.MEAL, HealthPhase.RESPOND, true);
        RecommendationTopicPolicy.Decision decision = RecommendationTopicPolicy.decide(state,
                Map.of("mealTime", List.of("午餐")), Map.of("mealTime", List.of("午餐"), "healthGoal", List.of("清淡")),
                "午餐");
        assertEquals(RecommendationTopicPolicy.Reason.CLARIFY_INHERIT, decision.reason());
        assertEquals(List.of("清淡"), decision.slots().get("healthGoal"));
    }

    @Test
    void 显式重置表达只保留本轮槽位() {
        HealthSessionState state = state(HealthDomain.MEAL, HealthPhase.RESPOND, false);
        RecommendationTopicPolicy.Decision decision = RecommendationTopicPolicy.decide(state,
                Map.of("healthGoal", List.of("增肌")), HISTORY, "重置条件，推荐增肌餐");
        assertEquals(RecommendationTopicPolicy.Reason.CLEAR_RESET, decision.reason());
        assertEquals(1, decision.slots().size(), "重置后只保留本轮槽位");
        assertFalse(decision.slots().containsKey("mealTime"), "重置后历史餐次不得保留");
        assertEquals(List.of("增肌"), decision.slots().get("healthGoal"));
    }

    @Test
    void 只看餐次时历史只保留餐次维度() {
        HealthSessionState state = state(HealthDomain.MEAL, HealthPhase.RESPOND, false);
        RecommendationTopicPolicy.Decision decision = RecommendationTopicPolicy.decide(state,
                Map.of("mealTime", List.of("午餐")), HISTORY, "只看午餐");
        assertEquals(RecommendationTopicPolicy.Reason.LOOK_ONLY_MEAL_TIME, decision.reason());
        assertEquals(1, decision.slots().size());
        assertEquals(List.of("午餐"), decision.slots().get("mealTime"));
    }

    @Test
    void 餐食新推荐带餐次时旧偏好被替换() {
        HealthSessionState state = state(HealthDomain.MEAL, HealthPhase.RESPOND, false);
        RecommendationTopicPolicy.Decision decision = RecommendationTopicPolicy.decide(state,
                Map.of("mealTime", List.of("午餐")), HISTORY, "中午吃什么");
        assertEquals(RecommendationTopicPolicy.Reason.MEAL_TOPIC_RESET, decision.reason());
        assertEquals(1, decision.slots().size(), "换主题时历史非餐次偏好不得保留");
        assertEquals(List.of("午餐"), decision.slots().get("mealTime"));
    }

    @Test
    void 无显式餐次的新请求走普通合并继承上下文() {
        HealthSessionState state = state(HealthDomain.MEAL, HealthPhase.RESPOND, false);
        RecommendationTopicPolicy.Decision decision = RecommendationTopicPolicy.decide(state,
                Map.of("healthGoal", List.of("清淡")), HISTORY, "还有什么清淡的");
        assertEquals(RecommendationTopicPolicy.Reason.PLAIN_MERGE, decision.reason());
        assertEquals(List.of("晚餐"), decision.slots().get("mealTime"), "无餐次的继续补充必须保留历史餐次上下文");
        assertEquals(List.of("清淡"), decision.slots().get("healthGoal"));
    }

    @Test
    void 健身域不触发餐次重置且普通短答合并保留() {
        HealthSessionState state = state(HealthDomain.EXERCISE, HealthPhase.RESPOND, false);
        Map<String, List<String>> merged = Map.of("bodyParts", List.of("胸"), "equipment", List.of("徒手"));
        RecommendationTopicPolicy.Decision decision = RecommendationTopicPolicy.decide(state,
                Map.of("bodyParts", List.of("背")), merged, "改练背");
        assertEquals(RecommendationTopicPolicy.Reason.PLAIN_MERGE, decision.reason());
        assertEquals(List.of("徒手"), decision.slots().get("equipment"), "健身域新请求保留其他已确认维度");
    }

    @Test
    void 换成餐次是显式逐槽位修改不重置其他维度() {
        HealthSessionState state = state(HealthDomain.MEAL, HealthPhase.RESPOND, false);
        RecommendationTopicPolicy.Decision decision = RecommendationTopicPolicy.decide(state,
                Map.of("mealTime", List.of("午餐")),
                Map.of("mealTime", List.of("午餐"), "healthGoal", List.of("清淡")), "换成午餐");
        assertEquals(RecommendationTopicPolicy.Reason.PLAIN_MERGE, decision.reason());
        assertEquals(List.of("午餐"), decision.slots().get("mealTime"));
        assertEquals(List.of("清淡"), decision.slots().get("healthGoal"), "换成只作用于目标槽位，其他维度保留");
    }

    @Test
    void 替换词由既有逐槽位覆盖承担不进入专用分支() {
        HealthSessionState state = state(HealthDomain.EXERCISE, HealthPhase.RESPOND, false);
        RecommendationTopicPolicy.Decision decision = RecommendationTopicPolicy.decide(state,
                Map.of("equipment", List.of("哑铃")),
                Map.of("difficulty", List.of("入门"), "equipment", List.of("哑铃")), "换成哑铃");
        assertEquals(RecommendationTopicPolicy.Reason.PLAIN_MERGE, decision.reason());
        assertEquals(List.of("哑铃"), decision.slots().get("equipment"), "换成/改为 由逐槽位覆盖承担");
        assertEquals(List.of("入门"), decision.slots().get("difficulty"), "替换只作用于目标槽位");
    }

    @Test
    void 适用范围只覆盖同域推荐轮() {
        assertTrue(RecommendationTopicPolicy.applies(HealthDomain.MEAL, HealthDomain.MEAL, HealthTask.RECOMMEND));
        assertTrue(RecommendationTopicPolicy.applies(HealthDomain.EXERCISE, HealthDomain.EXERCISE, HealthTask.RECOMMEND));
        assertFalse(RecommendationTopicPolicy.applies(HealthDomain.MEAL, HealthDomain.ROUTINE, HealthTask.RECOMMEND),
                "跨域切换走既有投影与暂停语义");
        assertFalse(RecommendationTopicPolicy.applies(HealthDomain.MEAL, HealthDomain.MEAL, HealthTask.PLAN),
                "计划简报不受推荐策略影响");
        assertFalse(RecommendationTopicPolicy.applies(null, HealthDomain.MEAL, HealthTask.RECOMMEND));
    }
}