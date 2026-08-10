package com.diet.health.plan;

import com.diet.agent.contract.AgentContractModule;
import com.diet.agent.invoker.AgentInvoker;
import com.diet.agent.invoker.FixtureAgentInvoker;
import com.diet.agent.loader.PromptLoader;
import com.diet.health.profile.HealthProfileService.HealthProfileView;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlanResponseAgent（34 号，输出后 Guard）：
 * 正常输出透传并附估算免责声明；非法 JSON/未知结构/候选越界/kcal 声明/医疗结论词
 * 全部立即模板降级，复用 32 号 Agent 契约模块与失败分类。
 */
class HealthPlanResponseAgentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentTraceService trace = mock(AgentTraceService.class);
    private AgentContractModule contract;

    private static final HealthProfileView PROFILE = new HealthProfileView(1L, 30, com.diet.health.enums.ProfileSex.MALE,
            175.0, 70.0, com.diet.health.enums.ActivityLevel.LIGHT, com.diet.health.enums.ProfileGoal.MAINTAIN,
            "Asia/Shanghai", 2150, 2400, true, 1L, "basis");

    private static final List<PlanItemView> ITEMS = List.of(
            new PlanItemView(1L, "EXERCISE", "9001", "俯卧撑", LocalDate.of(2026, 8, 17),
                    LocalTime.of(19, 30), LocalTime.of(21, 0), null, Map.of("bodyPart", "胸")),
            new PlanItemView(2L, "MEAL", "5", "清蒸鲈鱼", LocalDate.of(2026, 8, 17),
                    LocalTime.of(12, 0), LocalTime.of(13, 0), null, Map.of("caloriesKcal", 600))
    );

    private HealthPlanResponseAgentService service;

    @BeforeEach
    void setUp() {
        AgentTraceService realTrace = new AgentTraceService(mock(com.diet.mapper.AgentTraceMapper.class), objectMapper);
        contract = new AgentContractModule(new FixtureAgentInvoker(planResolver(), "v1"), new LlmJsonService(objectMapper), realTrace);
        service = new HealthPlanResponseAgentService(contract, new PromptLoader(), "qwen-max", "v1", 1000);
    }

    private static FixtureAgentInvoker.FixtureResolver planResolver() {
        return invocation -> {
            String prompt = userSegment(invocation);
            if (prompt.contains("非法JSON")) {
                return "这不是 JSON";
            }
            if (prompt.contains("缺speech")) {
                return "{\"highlightIds\":[]}";
            }
            if (prompt.contains("越界ID")) {
                return "{\"speechText\":\"计划说明\",\"highlightIds\":[\"9999\"]}";
            }
            if (prompt.contains("自主kcal")) {
                return "{\"speechText\":\"每天摄入 2200 大卡比较合适\",\"highlightIds\":[]}";
            }
            if (prompt.contains("医疗词")) {
                return "{\"speechText\":\"这个计划可以治疗你的症状\",\"highlightIds\":[]}";
            }
            if (prompt.contains("绝对化")) {
                return "{\"speechText\":\"保证你一个月练成\",\"highlightIds\":[]}";
            }
            return "{\"speechText\":\"本周安排了周一、周三两次训练，配合每日三餐和固定作息。\",\"highlightIds\":[\"9001\"]}";
        };
    }

    /** 只匹配用户输入段（项目列表之后），避免系统提示词污染关键词。 */
    private static String userSegment(AgentInvoker.AgentInvocation invocation) {
        int index = invocation.promptText().lastIndexOf("已校验计划项目: ");
        return index < 0 ? invocation.promptText() : invocation.promptText().substring(index);
    }

    private HealthPlanResponseAgentService.PlanExplanation explain(String marker) {
        return service.explain(PROFILE, List.of(
                new PlanItemView(1L, "EXERCISE", "9001", "俯卧撑 " + marker, ITEMS.get(0).localDate(),
                        ITEMS.get(0).startTime(), ITEMS.get(0).endTime(), null, ITEMS.get(0).params())
        ));
    }

    @Test
    void 正常输出透传并附估算免责声明() {
        HealthPlanResponseAgentService.PlanExplanation explanation = explain("正常");
        assertNull(explanation.fallbackReason());
        assertTrue(explanation.speechText().contains("估算值"));
        assertEquals(List.of("9001"), explanation.highlightIds());
    }

    @Test
    void 非法JSON立即模板降级() {
        HealthPlanResponseAgentService.PlanExplanation explanation = explain("非法JSON");
        assertNotNull(explanation.fallbackReason());
        assertTrue(explanation.fallbackReason().contains("INVALID_JSON"));
        assertTrue(explanation.speechText().contains("周计划草稿"));
    }

    @Test
    void 缺speechText为Schema违规() {
        HealthPlanResponseAgentService.PlanExplanation explanation = explain("缺speech");
        assertNotNull(explanation.fallbackReason());
        assertTrue(explanation.fallbackReason().contains("SCHEMA_VIOLATION"));
    }

    @Test
    void 候选越界为CANDIDATE_VIOLATION() {
        HealthPlanResponseAgentService.PlanExplanation explanation = explain("越界ID");
        assertNotNull(explanation.fallbackReason());
        assertTrue(explanation.fallbackReason().contains("CANDIDATE_VIOLATION"));
    }

    @Test
    void LLM自主kcal数值被Guard拦截() {
        HealthPlanResponseAgentService.PlanExplanation explanation = explain("自主kcal");
        assertNotNull(explanation.fallbackReason());
        assertTrue(explanation.fallbackReason().contains("kcal"));
    }

    @Test
    void LLM医疗结论词被Guard拦截() {
        HealthPlanResponseAgentService.PlanExplanation explanation = explain("医疗词");
        assertNotNull(explanation.fallbackReason());
        assertTrue(explanation.fallbackReason().contains("医疗结论"));
    }

    @Test
    void LLM绝对化用语被Guard拦截() {
        HealthPlanResponseAgentService.PlanExplanation explanation = explain("绝对化");
        assertNotNull(explanation.fallbackReason());
        assertTrue(explanation.fallbackReason().contains("绝对化"));
    }

    @Test
    void 模板只汇总项目不编造数值() {
        HealthPlanResponseAgentService.PlanExplanation explanation = explain("非法JSON");
        assertTrue(explanation.speechText().contains("共 1 个项目"));
    }
}
