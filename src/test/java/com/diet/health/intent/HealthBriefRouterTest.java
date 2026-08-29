package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.plan.MealPlanBrief;
import com.diet.health.plan.PlanBrief;
import com.diet.health.session.BriefLifecycle;
import com.diet.health.session.HealthSessionState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享结构化续轮判定（简报补充回路规格 v3.2）：
 * 断言 reason、escape、activeSide、briefActive，不只断言最终 domain/task；
 * 覆盖原始失败句、交叉输入优先级、生命周期与综合侧归属矩阵。
 */
class HealthBriefRouterTest {

    private final HealthBriefRouter router = new HealthBriefRouter();

    private HealthSessionState mealBriefSession() {
        // 活跃餐食简报会话：目标周+餐次已确认，生命周期 OPEN（持久化）
        return new HealthSessionState("s", 1L, HealthPhase.RESPOND, HealthDomain.MEAL, HealthTask.PLAN,
                List.of(), Map.of(), List.of(), List.of(), PlanBrief.empty(),
                new MealPlanBrief(LocalDate.of(2026, 8, 31), List.of("早餐", "午餐", "晚餐"), "减脂"),
                false, false, 0, Map.of("MEAL", BriefLifecycle.OPEN.name()), null);
    }

    private HealthSessionState compositeSession(MealPlanBrief meal, PlanBrief training) {
        return new HealthSessionState("s", 1L, HealthPhase.RESPOND, HealthDomain.COMPOSITE, HealthTask.PLAN,
                List.of(), Map.of(), List.of(), List.of(), training, meal,
                false, false, 0, Map.of("MEAL", BriefLifecycle.OPEN.name(), "EXERCISE", BriefLifecycle.OPEN.name()),
                null);
    }

    @Test
    void 活跃简报中的偏好补充进入简报处理器且命中字段解析优先级() {
        HealthSessionState state = mealBriefSession();

        BriefRoutingDecision decision = router.decide(state, "我喜欢清淡的餐食");
        assertTrue(decision.briefActive());
        assertEquals(BriefEscape.NONE, decision.escape());
        assertEquals(BriefSide.MEAL, decision.activeSide());
        assertEquals("MEAL_BRIEF_FOCUS", decision.reason());
    }

    @Test
    void 原始失败句不含逃生口且归属餐食侧() {
        HealthSessionState state = mealBriefSession();
        // “我喜欢中餐”：中餐是未支持菜系而非领域切换词，不得被路由判断降级
        BriefRoutingDecision cuisine = router.decide(state, "我喜欢中餐");
        assertTrue(cuisine.briefActive());
        assertEquals(BriefEscape.NONE, cuisine.escape());
        assertEquals(BriefSide.MEAL, cuisine.activeSide());

        BriefRoutingDecision convenience = router.decide(state, "烹饪时间短");
        assertTrue(convenience.briefActive());
        assertEquals(BriefEscape.NONE, convenience.escape());
        assertEquals(BriefSide.MEAL, convenience.activeSide());
    }

    @Test
    void 明确普通推荐请求触发RECOMMEND逃生口() {
        HealthSessionState state = mealBriefSession();
        BriefRoutingDecision decision = router.decide(state, "有什么推荐");
        assertTrue(decision.briefActive());
        assertEquals(BriefEscape.RECOMMEND, decision.escape());
        assertEquals("EXPLICIT_RECOMMEND_REQUEST", decision.reason());
    }

    @Test
    void 替代推荐触发ALTERNATIVE逃生口() {
        HealthSessionState state = mealBriefSession();
        BriefRoutingDecision decision = router.decide(state, "换一批");
        assertTrue(decision.briefActive());
        assertEquals(BriefEscape.ALTERNATIVE, decision.escape());
        assertEquals("ALTERNATIVE_REQUEST", decision.reason());
    }

    @Test
    void 作息提问触发切域逃生口且优先于推荐词() {
        HealthSessionState state = mealBriefSession();
        BriefRoutingDecision routine = router.decide(state, "晚上几点前停止喝咖啡？");
        assertTrue(routine.briefActive());
        assertEquals(BriefEscape.DOMAIN_OR_ROUTINE, routine.escape());
        assertEquals("EXPLICIT_DOMAIN_OR_ROUTINE_SWITCH", routine.reason());
        assertEquals(HealthDomain.ROUTINE, routine.escapeDomain());

        // 交叉输入：切域/作息优先级高于替代与普通推荐
        BriefRoutingDecision crossed = router.decide(state, "几点睡比较好，顺便换个推荐的菜");
        assertEquals(BriefEscape.DOMAIN_OR_ROUTINE, crossed.escape());
    }

    @Test
    void 跨域证据触发切域逃生口() {
        HealthSessionState state = mealBriefSession();
        BriefRoutingDecision decision = router.decide(state, "我想练背");
        assertTrue(decision.briefActive());
        assertEquals(BriefEscape.DOMAIN_OR_ROUTINE, decision.escape());
        assertEquals(HealthDomain.EXERCISE, decision.escapeDomain());
    }

    @Test
    void 已生成生命周期不捕获自由文本且生成后谢谢不重新捕获() {
        HealthSessionState generated = new HealthSessionState("s", 1L, HealthPhase.RESPOND, HealthDomain.MEAL,
                HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(), PlanBrief.empty(),
                new MealPlanBrief(LocalDate.of(2026, 8, 31), List.of("早餐"), "减脂"),
                false, false, 0, Map.of("MEAL", BriefLifecycle.GENERATED.name()), null);
        BriefRoutingDecision decision = router.decide(generated, "清淡点");
        assertFalse(decision.briefActive());
        assertEquals(BriefEscape.NONE, decision.escape());
        assertEquals("LIFECYCLE_GENERATED", decision.reason());

        BriefRoutingDecision thanks = router.decide(generated, "谢谢");
        assertFalse(thanks.briefActive(), "生成后普通社交短句不能重新捕获已生成简报");
    }

    @Test
    void 暂停生命周期不捕获且显式计划词重新打开() {
        HealthSessionState paused = new HealthSessionState("s", 1L, HealthPhase.RESPOND, HealthDomain.MEAL,
                HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(), PlanBrief.empty(),
                new MealPlanBrief(LocalDate.of(2026, 8, 31), List.of("早餐"), "减脂"),
                false, false, 0, Map.of("MEAL", BriefLifecycle.PAUSED.name()), null);
        BriefRoutingDecision pausedDecision = router.decide(paused, "清淡点");
        assertFalse(pausedDecision.briefActive());
        assertEquals("LIFECYCLE_PAUSED", pausedDecision.reason());

        BriefRoutingDecision reopen = router.decide(paused, "再调整餐食计划");
        assertTrue(reopen.briefActive(), "显式计划语句可以重新打开对应简报");
        assertEquals(BriefEscape.NONE, reopen.escape());
        assertEquals(BriefSide.MEAL, reopen.activeSide());
    }

    @Test
    void 生成态下显式计划词重新打开对应侧() {
        HealthSessionState generated = new HealthSessionState("s", 1L, HealthPhase.RESPOND, HealthDomain.MEAL,
                HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(), PlanBrief.empty(),
                new MealPlanBrief(LocalDate.of(2026, 8, 31), List.of("早餐"), "减脂"),
                false, false, 0, Map.of("MEAL", BriefLifecycle.GENERATED.name()), null);
        BriefRoutingDecision reopen = router.decide(generated, "再调整餐食计划");
        assertTrue(reopen.briefActive());
        assertEquals(BriefSide.MEAL, reopen.activeSide());
    }

    @Test
    void 综合侧归属矩阵() {
        // NONE（两侧皆空）→ 默认写入餐食侧
        BriefRoutingDecision none = router.decide(compositeSession(MealPlanBrief.empty(), PlanBrief.empty()), "清淡点");
        assertTrue(none.briefActive());
        assertEquals(BriefSide.MEAL, none.activeSide());
        assertEquals("MEAL_BRIEF_FOCUS", none.reason());

        // MEAL 焦点：餐食未完整 → MEAL
        BriefRoutingDecision mealFocus = router.decide(compositeSession(MealPlanBrief.empty(), PlanBrief.empty()),
                "早餐安排清淡的");
        assertEquals(BriefSide.MEAL, mealFocus.activeSide());

        // EXERCISE 焦点：餐食完整、训练未完整 → EXERCISE
        MealPlanBrief completeMeal = new MealPlanBrief(LocalDate.of(2026, 8, 31), List.of("早餐"), "减脂");
        BriefRoutingDecision exerciseFocus = router.decide(compositeSession(completeMeal, PlanBrief.empty()),
                "重点练胸");
        assertEquals(BriefSide.EXERCISE, exerciseFocus.activeSide());

        // BOTH：两侧都完整且无前缀 → 不猜测，要求侧前缀
        PlanBrief completeTraining = new PlanBrief("增肌", List.of("胸"), List.of("徒手"), "入门",
                LocalDate.of(2026, 8, 31), List.of(java.time.DayOfWeek.MONDAY),
                new com.diet.health.plan.TrainingTimeWindow(java.time.LocalTime.of(19, 0), java.time.LocalTime.of(20, 0)),
                Map.of(), null, 0, null);
        BriefRoutingDecision both = router.decide(compositeSession(completeMeal, completeTraining), "改成下周");
        assertTrue(both.briefActive());
        assertEquals(BriefSide.BOTH, both.activeSide());
        assertEquals("COMPOSITE_BOTH_NEED_SIDE_PREFIX", both.reason());
        assertEquals(BriefEscape.NONE, both.escape());
    }

    @Test
    void 综合显式侧前缀优先且允许跨侧修改() {
        MealPlanBrief completeMeal = new MealPlanBrief(LocalDate.of(2026, 8, 31), List.of("早餐"), "减脂");
        PlanBrief completeTraining = new PlanBrief("增肌", List.of("胸"), List.of("徒手"), "入门",
                LocalDate.of(2026, 8, 31), List.of(java.time.DayOfWeek.MONDAY),
                new com.diet.health.plan.TrainingTimeWindow(java.time.LocalTime.of(19, 0), java.time.LocalTime.of(20, 0)),
                Map.of(), null, 0, null);
        // 两侧完整后“餐食：清淡”仍显式归属餐食侧
        BriefRoutingDecision mealPrefix = router.decide(compositeSession(completeMeal, completeTraining),
                "餐食：清淡");
        assertEquals(BriefSide.MEAL, mealPrefix.activeSide());

        // 两侧完整后“训练：改练背”显式归属训练侧
        BriefRoutingDecision exercisePrefix = router.decide(compositeSession(completeMeal, completeTraining),
                "训练：改练背");
        assertEquals(BriefSide.EXERCISE, exercisePrefix.activeSide());
    }

    @Test
    void 无简报上下文不活跃() {
        // 新会话（推荐澄清阶段）没有计划简报上下文
        HealthSessionState recommend = new HealthSessionState("s", 1L, HealthPhase.CLARIFY, HealthDomain.MEAL,
                HealthTask.RECOMMEND, List.of(), Map.of("mealTime", List.of("晚餐")), List.of(), List.of(), null);
        BriefRoutingDecision decision = router.decide(recommend, "清淡点");
        assertFalse(decision.briefActive());
        assertEquals(BriefSide.NONE, decision.activeSide());
        assertEquals("NO_ACTIVE_BRIEF", decision.reason());
    }

    @Test
    void 推荐请求词清单唯一且计划词优先() {
        assertTrue(router.isRecommendationRequest("有什么推荐"));
        assertTrue(router.isRecommendationRequest("看看今晚吃什么"));
        assertFalse(router.isRecommendationRequest("帮我安排一周餐食计划"), "含“计划”时不得触发推荐逃生口");
        assertTrue(router.isAlternativeRequest("换一批"));
        assertFalse(router.isAlternativeRequest("推荐"));
    }

    @Test
    void 社交短句小清单() {
        assertTrue(router.isSocialPhrase("谢谢"));
        assertTrue(router.isSocialPhrase("好的"));
        assertTrue(router.isSocialPhrase("明白了"));
        assertFalse(router.isSocialPhrase("谢谢，再帮我看看清淡的"));
        assertFalse(router.isSocialPhrase("清淡"));
    }
}
