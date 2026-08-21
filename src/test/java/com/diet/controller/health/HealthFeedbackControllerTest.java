package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.exception.HealthApiExceptionHandler;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.feedback.HealthFeedbackService;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.model.HealthDisplayBlock;
import com.diet.health.model.HealthFeedbackRequest;
import com.diet.health.module.HealthResource;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.FeedbackMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.RequestTraceRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 健康反馈 HTTP 入口测试（41 号票 + #65 + #74）：
 * 五种合法 action（含 UNFAVORITE）经真实服务持久化；非法 action 在 HTTP 层返回 400
 * 且错误文案列出五种 action；资源不存在返回 404；
 * #74：非法 traceId（不存在/session 不匹配）返回 404，资源不在该 trace 推荐结果返回 400，
 * 合法 traceId 返回 200。
 */
class HealthFeedbackControllerTest {

    private static final long USER = 100L;

    private final FeedbackMapper feedbackMapper = mock(FeedbackMapper.class);
    private final WeeklyPlanMapper planMapper = mock(WeeklyPlanMapper.class);
    private final HealthResourceProvider provider = mock(HealthResourceProvider.class);
    private final AgentTraceService traceService = mock(AgentTraceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    private static final HealthResource MEAL_5 = new HealthResource(
            "MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, java.util.Map.of());

    @BeforeEach
    void setUp() throws Exception {
        HealthFeedbackService service =
                new HealthFeedbackService(feedbackMapper, planMapper, provider, traceService, objectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthFeedbackController(service))
                .setControllerAdvice(new HealthApiExceptionHandler())
                .build();
        // 默认提供一条归属用户且 session 匹配、推荐了 MEAL:5 的 trace，供 traceId 用例复用。
        when(traceService.findByTraceId(USER, "trace-1")).thenReturn(traceRow("trace-1", "sess-1", List.of(
                new HealthDisplayBlock("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, null))));
    }

    private RequestTraceRow traceRow(String traceId, String sessionId, List<HealthDisplayBlock> blocks) throws Exception {
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId(traceId);
        row.setSessionId(sessionId);
        row.setResponseJson(objectMapper.writeValueAsString(HealthChatResponse.answer(
                sessionId, traceId, HealthDomain.MEAL, HealthTask.RECOMMEND, List.of(),
                HealthPhase.RESPOND, "推荐如下", blocks)));
        return row;
    }

    private org.springframework.test.web.servlet.ResultActions postFeedback(String action, String resourceId) throws Exception {
        String body = "{\"sessionId\":\"sess-1\",\"resourceType\":\"MEAL\",\"resourceId\":\"" + resourceId
                + "\",\"action\":\"" + action + "\"}";
        return mockMvc.perform(post("/api/v1/health/feedback")
                .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 五种合法action全部返回200() throws Exception {
        when(provider.mealById("5")).thenReturn(Optional.of(MEAL_5));
        for (String action : new String[]{"LIKE", "DISLIKE", "FAVORITE", "UNFAVORITE", "ADOPT"}) {
            postFeedback(action, "5").andExpect(status().isOk());
        }
        verify(feedbackMapper, times(5)).insertTyped(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 非法action返回400且文案列出兼容与新反馈action() throws Exception {
        when(provider.mealById("5")).thenReturn(Optional.of(MEAL_5));
        postFeedback("THUMBS_UP", "5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("反馈 action 必须为 LIKE/DISLIKE/FAVORITE/UNFAVORITE/ADOPT/REDUCE_RECOMMENDATION/UNDO_REDUCE_RECOMMENDATION"));
    }

    @Test
    void 资源不存在返回404() throws Exception {
        when(provider.mealById("999")).thenReturn(Optional.empty());
        postFeedback("FAVORITE", "999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 会话级反馈无资源也可写入() throws Exception {
        mockMvc.perform(post("/api/v1/health/feedback")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"sess-1\",\"action\":\"LIKE\",\"rating\":4}"))
                .andExpect(status().isOk());
        verify(feedbackMapper).insertTyped(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---------- #74 traceId 精确归因 HTTP 层 ----------

    @Test
    void traceId不存在返回404且不写入() throws Exception {
        when(traceService.findByTraceId(USER, "trace-ghost")).thenReturn(null);
        mockMvc.perform(post("/api/v1/health/feedback")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"sess-1\",\"traceId\":\"trace-ghost\","
                                + "\"resourceType\":\"MEAL\",\"resourceId\":\"5\",\"action\":\"LIKE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Trace 不存在或无权访问"));
        verify(feedbackMapper, times(0)).insertTyped(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void traceId与sessionId不匹配返回404且不写入() throws Exception {
        when(traceService.findByTraceId(USER, "trace-1")).thenReturn(traceRow("trace-1", "sess-other", List.of(
                new HealthDisplayBlock("MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, null))));
        mockMvc.perform(post("/api/v1/health/feedback")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"sess-1\",\"traceId\":\"trace-1\","
                                + "\"resourceType\":\"MEAL\",\"resourceId\":\"5\",\"action\":\"LIKE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(feedbackMapper, times(0)).insertTyped(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void traceId资源不在该trace推荐结果中返回400且不写入() throws Exception {
        mockMvc.perform(post("/api/v1/health/feedback")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"sess-1\",\"traceId\":\"trace-1\","
                                + "\"resourceType\":\"MEAL\",\"resourceId\":\"999\",\"action\":\"LIKE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verify(feedbackMapper, times(0)).insertTyped(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 合法traceId返回200并携带traceId写入() throws Exception {
        mockMvc.perform(post("/api/v1/health/feedback")
                        .requestAttr(DietConstants.USER_ID_ATTRIBUTE, USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"sess-1\",\"traceId\":\"trace-1\","
                                + "\"resourceType\":\"MEAL\",\"resourceId\":\"5\",\"action\":\"LIKE\",\"rating\":5}"))
                .andExpect(status().isOk());
        verify(feedbackMapper).insertTyped(
                any(), any(), org.mockito.ArgumentMatchers.eq("trace-1"),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
