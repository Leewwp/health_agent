package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.session.HealthSessionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话续轮必须在调用模型前完成上下文继承。 */
class HealthIntentRevisionServiceTest {

    private final HealthIntentRevisionService service = new HealthIntentRevisionService(new HealthInputNormalizer(), new HealthBriefRouter());

    @Test
    void 餐食澄清短答直接继承当前领域并抽取槽位() {
        HealthSessionState state = new HealthSessionState("s", 1L, HealthPhase.CLARIFY,
                HealthDomain.MEAL, HealthTask.RECOMMEND, List.of(),
                Map.of("mealTime", List.of("晚餐")), List.of(), List.of(), null);

        // 2026-08-31 严格路由规格：澄清短答继承需解析出字段值或含任务证据，
        // 用例输入从“按口味”（槽位名、非字段值，按新边界不再继承）改为真实字段短答。
        HealthIntentResult result = service.continueBeforeAgent("清淡一点", state).orElseThrow();
        assertEquals(HealthDomain.MEAL, result.domain());
        assertEquals(HealthTask.RECOMMEND, result.task());
        assertFalse(result.slots().isEmpty(), "澄清短答应抽取槽位");
    }

    @Test
    void 训练计划每个简报回答均直接继承PLAN上下文() {
        HealthSessionState state = new HealthSessionState("s", 1L, HealthPhase.CLARIFY,
                HealthDomain.EXERCISE, HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(), null);

        HealthIntentResult result = service.continueBeforeAgent("减脂", state).orElseThrow();
        assertEquals(HealthDomain.EXERCISE, result.domain());
        assertEquals(HealthTask.PLAN, result.task());
    }

    @Test
    void 训练计划同领域显式槽位经后置修正仍保持PLAN上下文() {
        HealthSessionState state = new HealthSessionState("s", 1L, HealthPhase.CLARIFY,
                HealthDomain.EXERCISE, HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(), null);

        HealthIntentResult continued = service.continueBeforeAgent("全身", state).orElseThrow();
        HealthIntentRevisionService.Revision revision = service.revise("全身", state, continued);

        assertEquals(HealthDomain.EXERCISE, revision.intent().domain());
        assertEquals(HealthTask.PLAN, revision.intent().task());
    }

    @Test
    void 餐食计划澄清后的纯餐次回答仍保持MEAL_PLAN上下文() {
        HealthSessionState state = new HealthSessionState("s", 1L, HealthPhase.CLARIFY,
                HealthDomain.MEAL, HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(), null);

        HealthIntentResult continued = service.continueBeforeAgent("每天三餐", state).orElseThrow();
        HealthIntentRevisionService.Revision revision = service.revise("每天三餐", state, continued);

        assertEquals(HealthDomain.MEAL, revision.intent().domain());
        assertEquals(HealthTask.PLAN, revision.intent().task());
    }

    @Test
    void 明确切换领域时不继承旧上下文() {
        HealthSessionState state = new HealthSessionState("s", 1L, HealthPhase.CLARIFY,
                HealthDomain.EXERCISE, HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(), null);
        assertTrue(service.continueBeforeAgent("改为推荐晚餐", state).isEmpty());
    }

    @Test
    void 计划中切到同领域餐食推荐时不误续为餐食计划() {
        HealthSessionState state = new HealthSessionState("s", 1L, HealthPhase.CLARIFY,
                HealthDomain.MEAL, HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(), null);
        assertTrue(service.continueBeforeAgent("先帮我看看今晚清淡的晚餐", state).isEmpty());

        HealthIntentResult raw = HealthIntentResult.parsed(HealthDomain.MEAL, HealthTask.PLAN,
                List.of(), Map.of(), List.of(), 1.0);
        HealthIntentRevisionService.Revision revision =
                service.revise("先帮我看看今晚清淡的晚餐", state, raw);
        assertEquals(HealthTask.RECOMMEND, revision.intent().task());
    }

    @Test
    void 从餐食话题返回训练计划时恢复PLAN上下文() {
        HealthSessionState state = new HealthSessionState("s", 1L, HealthPhase.RESPOND,
                HealthDomain.MEAL, HealthTask.RECOMMEND, List.of(), Map.of(), List.of(), List.of(), null);
        HealthIntentResult raw = HealthIntentResult.parsed(HealthDomain.EXERCISE, HealthTask.PLAN,
                List.of(), Map.of(), List.of(), 1.0);
        HealthIntentRevisionService.Revision revision =
                service.revise("回到训练计划，目标增肌，周一二三，下午六点至七点", state, raw);
        assertEquals(HealthDomain.EXERCISE, revision.intent().domain());
        assertEquals(HealthTask.PLAN, revision.intent().task());
    }

    @Test
    void 明确训练加餐食计划被修正为COMPOSITE_PLAN() {
        HealthSessionState state = HealthSessionState.fresh("s", 1L);
        HealthIntentResult raw = HealthIntentResult.parsed(HealthDomain.MEAL, HealthTask.RECOMMEND,
                List.of(), Map.of(), List.of(), 1.0);

        HealthIntentRevisionService.Revision revision = service.revise("帮我安排一周训练和餐食计划", state, raw);

        assertEquals(HealthDomain.COMPOSITE, revision.intent().domain());
        assertEquals(HealthTask.PLAN, revision.intent().task());
    }

    @Test
    void 训练简报带时间字段仍保持EXERCISE_PLAN() {
        HealthSessionState state = HealthSessionState.fresh("s", 1L);
        String input = "训练偏好已整理：训练目标：增肌；部位：全身；器械：哑铃；难度：进阶；目标周：2026-08-24；训练日：周一、周二、周三、周四、周五；时间：18:00-19:00。请确认后生成计划。";
        HealthIntentResult raw = HealthIntentResult.parsed(HealthDomain.ROUTINE, HealthTask.PLAN,
                List.of(), Map.of(), List.of(), 1.0);
        HealthIntentRevisionService.Revision revision = service.revise(input, state, raw);
        assertEquals(HealthDomain.EXERCISE, revision.intent().domain());
        assertEquals(HealthTask.PLAN, revision.intent().task());
    }
}
