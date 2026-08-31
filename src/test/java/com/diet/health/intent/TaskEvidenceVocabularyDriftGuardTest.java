package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.session.HealthSessionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务证据词表漂移守卫（2026-08-31 严格路由规格 RC-7）：
 * 替代/调整词、推荐确认短语、追加计划词与聊天逃生表达只有一个所有者（HealthTaskEvidence），
 * 意图规则与意图修订的行为口径由本测试固定——新增任务词必须落在共享所有者，
 * 不得在任何消费方再建私有清单。
 */
class TaskEvidenceVocabularyDriftGuardTest {

    private final HealthTaskEvidence evidence = new HealthTaskEvidence();
    private final HealthInputNormalizer normalizer = new HealthInputNormalizer();
    private final IntentRuleService ruleService = new IntentRuleService(normalizer);
    private final HealthIntentRevisionService revisionService =
            new HealthIntentRevisionService(normalizer, new HealthBriefRouter());

    private HealthSessionState mealRecommendSession() {
        return new HealthSessionState("s", 1L, HealthPhase.CLARIFY, HealthDomain.MEAL,
                HealthTask.RECOMMEND, List.of(), Map.of("mealTime", List.of("晚餐")), List.of(), List.of(),
                null, null, false, false, 0, Map.of(), null, null, null);
    }

    @Test
    void 替代请求词在意图规则与意图修订中口径一致() {
        for (String word : HealthTaskEvidence.adjustRequestWords()) {
            HealthIntentResult fallback = ruleService.fallback(word, Map.of(), null);
            assertEquals(HealthTask.ADJUST, fallback.task(), "意图规则兜底必须把共享调整词判为 ADJUST：" + word);

            HealthIntentResult raw = HealthIntentResult.parsed(HealthDomain.OTHER, HealthTask.CHAT,
                    List.of(), Map.of(), List.of(), 0.2);
            HealthIntentRevisionService.Revision revision = revisionService.revise(word, mealRecommendSession(), raw);
            assertEquals(HealthTask.ADJUST, revision.intent().task(), "意图修订必须把共享调整词判为 ADJUST：" + word);
            assertEquals(HealthDomain.MEAL, revision.intent().domain(), "调整词继承推荐域：" + word);
        }
    }

    @Test
    void 推荐确认短语清单完整且被编排器消费() {
        List<String> phrases = HealthTaskEvidence.recommendationConfirmationWords();
        assertEquals(List.of("为我推荐", "可以推荐了", "确认推荐", "就这样推荐", "开始推荐", "按这个推荐"), phrases,
                "确认短语口径变化必须同步规格与前端按钮文案（“开始推荐”）");
        for (String phrase : phrases) {
            assertTrue(evidence.isRecommendationConfirmation(phrase), phrase);
            assertTrue(evidence.isRecommendationConfirmation("  " + phrase + " 吧 "), "确认短语匹配容忍空白：" + phrase);
        }
        assertFalse(evidence.isRecommendationConfirmation("随便聊聊"));
    }

    @Test
    void 追加计划词与聊天逃生词清单行为正确() {
        for (String word : HealthTaskEvidence.appendToPlanWords()) {
            assertTrue(evidence.isAppendToCurrentPlanExpression(word), word);
        }
        assertFalse(evidence.isAppendToCurrentPlanExpression("新建一份"));

        for (String expression : HealthTaskEvidence.chatEscapeExpressions()) {
            assertTrue(evidence.isChatEscapeExpression(expression), expression);
            // 逃生表达必须打断澄清继承（continueBeforeAgent 返回空，交完整意图链的 CHAT 路由）
            Optional<HealthIntentResult> inherited = revisionService.continueBeforeAgent(expression, mealRecommendSession());
            assertTrue(inherited.isEmpty(), "聊天逃生表达不得被澄清继承捕获：" + expression);
        }
        assertFalse(evidence.isChatEscapeExpression("推荐晚餐"));
    }

    @Test
    void 推荐请求动词构成跨领域任务证据() {
        assertTrue(evidence.hasRecommendRequestEvidence("有什么推荐吗"));
        assertTrue(evidence.hasRecommendRequestEvidence("给我推荐一份餐食"));
        assertFalse(evidence.hasRecommendRequestEvidence("今天天气如何"));
    }

    @Test
    void 作息活动词覆盖口语练字() {
        assertTrue(evidence.hasRoutineTaskEvidence("什么时候练合适"), "口语“练”必须与“训练”同口径");
        assertTrue(evidence.hasRoutineTaskEvidence("晚上几点后停止锻炼"));
        assertFalse(evidence.hasRoutineTaskEvidence("练胸"), "无时间词的活动表达不是作息事实");
    }
}
