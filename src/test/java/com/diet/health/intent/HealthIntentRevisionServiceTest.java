package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.session.HealthSessionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话续轮必须在调用模型前完成上下文继承。 */
class HealthIntentRevisionServiceTest {

    private final HealthIntentRevisionService service = new HealthIntentRevisionService(new HealthInputNormalizer());

    @Test
    void 餐食澄清短答直接继承当前领域并抽取槽位() {
        HealthSessionState state = new HealthSessionState("s", 1L, HealthPhase.CLARIFY,
                HealthDomain.MEAL, HealthTask.RECOMMEND, List.of(),
                Map.of("mealTime", List.of("晚餐")), List.of(), List.of(), null);

        HealthIntentResult result = service.continueBeforeAgent("按口味", state).orElseThrow();
        assertEquals(HealthDomain.MEAL, result.domain());
        assertEquals(HealthTask.RECOMMEND, result.task());
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
    void 明确切换领域时不继承旧上下文() {
        HealthSessionState state = new HealthSessionState("s", 1L, HealthPhase.CLARIFY,
                HealthDomain.EXERCISE, HealthTask.PLAN, List.of(), Map.of(), List.of(), List.of(), null);
        assertTrue(service.continueBeforeAgent("改为推荐晚餐", state).isEmpty());
    }
}
