package com.diet.health.feedback;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.HealthDomain;
import com.diet.health.enums.HealthPhase;
import com.diet.health.enums.HealthTask;
import com.diet.health.model.HealthChatResponse;
import com.diet.health.model.HealthDisplayBlock;
import com.diet.health.model.HealthFeedbackRequest;
import com.diet.health.module.HealthResource;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.FeedbackMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.RequestTraceRow;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 健康反馈服务（41 号票 + #65 + #74）：
 * 类型化字段写入、action/资源类型白名单、资源存在性校验、
 * 周计划归属与当前版本校验、资源与计划项目一致性、来源缺省；
 * #74 traceId 归因：Trace 不存在/session 不匹配 → 404 不写入，
 * 资源缺失或不在该 trace 推荐结果中 → 400 不写入，合法 traceId 原样落库。
 */
class HealthFeedbackServiceTest {

    private final FeedbackMapper feedbackMapper = mock(FeedbackMapper.class);
    private final WeeklyPlanMapper planMapper = mock(WeeklyPlanMapper.class);
    private final HealthResourceProvider provider = mock(HealthResourceProvider.class);
    private final AgentTraceService traceService = mock(AgentTraceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HealthFeedbackService service =
            new HealthFeedbackService(feedbackMapper, planMapper, provider, traceService, objectMapper);

    private static final HealthResource MEAL_5 = new HealthResource(
            "MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, Map.of());
    private static final HealthResource EXERCISE_9001 = new HealthResource(
            "EXERCISE", "9001", "俯卧撑", "PUBLIC", "Gym visual", null, true, Map.of());

    private WeeklyPlanRow plan(Long id, Long userId, Long currentVersion) {
        WeeklyPlanRow row = new WeeklyPlanRow();
        row.setId(id);
        row.setUserId(userId);
        row.setCurrentVersion(currentVersion);
        return row;
    }

    private WeeklyPlanItemRow item(Long id, Long planId, Long versionNo, String resourceType, String resourceId) {
        WeeklyPlanItemRow row = new WeeklyPlanItemRow();
        row.setId(id);
        row.setPlanId(planId);
        row.setVersionNo(versionNo);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        return row;
    }

    private RequestTraceRow trace(String traceId, String sessionId, String responseJson) {
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId(traceId);
        row.setSessionId(sessionId);
        row.setResponseJson(responseJson);
        return row;
    }

    /** 构造带指定展示块的 HealthChatResponse JSON，模拟 Trace 行 response_json。 */
    private String responseJson(String sessionId, List<HealthDisplayBlock> blocks) throws Exception {
        return objectMapper.writeValueAsString(HealthChatResponse.answer(
                sessionId, "trace-1", HealthDomain.MEAL, HealthTask.RECOMMEND, List.of(),
                HealthPhase.RESPOND, "推荐如下", blocks));
    }

    private static HealthDisplayBlock block(String resourceType, String resourceId) {
        return new HealthDisplayBlock(resourceType, resourceId, "测试资源", "PUBLIC", "来源", null, false, null);
    }

    @Test
    void 成功写入类型化字段与默认来源() {
        when(provider.mealById("5")).thenReturn(Optional.of(MEAL_5));
        service.save(1L, new HealthFeedbackRequest("sess-1", "MEAL", "5", "LIKE", null, null, 5, "好吃", null));

        verify(feedbackMapper).insertTyped(
                eq(1L), eq("sess-1"), eq(null),
                eq(null),
                eq("MEAL"), eq("5"),
                eq(null), eq(null),
                eq("LIKE"), eq(5), eq("好吃"),
                eq("HEALTH_CHAT"));
    }

    @Test
    void 显式source保留() {
        when(provider.exerciseById("9001")).thenReturn(Optional.of(EXERCISE_9001));
        service.save(1L, new HealthFeedbackRequest("sess-1", "EXERCISE", "9001", "FAVORITE",
                null, null, null, null, "HEALTH_CHAT"));

        verify(feedbackMapper).insertTyped(
                eq(1L), eq("sess-1"), eq(null),
                eq(null),
                eq("EXERCISE"), eq("9001"),
                eq(null), eq(null),
                eq("FAVORITE"), eq(null), eq(null),
                eq("HEALTH_CHAT"));
    }

    @Test
    void UNFAVORITE动作被接受并持久化() {
        when(provider.exerciseById("9001")).thenReturn(Optional.of(EXERCISE_9001));
        service.save(1L, new HealthFeedbackRequest("sess-1", "EXERCISE", "9001", "UNFAVORITE",
                null, null, null, null, null));

        verify(feedbackMapper).insertTyped(
                eq(1L), eq("sess-1"), eq(null),
                eq(null),
                eq("EXERCISE"), eq("9001"),
                eq(null), eq(null),
                eq("UNFAVORITE"), eq(null), eq(null),
                eq("HEALTH_CHAT"));
    }

    @Test
    void 五种action全部在白名单内() {
        when(provider.mealById("5")).thenReturn(Optional.of(MEAL_5));
        for (String action : List.of("LIKE", "DISLIKE", "FAVORITE", "UNFAVORITE", "ADOPT")) {
            service.save(1L, new HealthFeedbackRequest("sess-1", "MEAL", "5", action, null, null, null, null, null));
        }
        verify(feedbackMapper, org.mockito.Mockito.times(5)).insertTyped(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void action白名单外拒绝且不写入() {
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "MEAL", "5", "THUMBS_UP", null, null, null, null, null)));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("LIKE/DISLIKE/FAVORITE/UNFAVORITE/ADOPT"),
                "错误文案必须列出五种 action: " + error.getMessage());
        verify(feedbackMapper, never()).insertTyped(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ROUTINE资源类型拒绝() {
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "ROUTINE", "R1", "LIKE", null, null, null, null, null)));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("作息事实"), "文案应说明作息事实不参与偏好");
    }

    @Test
    void 资源类型白名单外拒绝() {
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "SUPPLEMENT", "X1", "LIKE", null, null, null, null, null)));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
    }

    @Test
    void 资源不存在返回NOT_FOUND() {
        when(provider.mealById("999")).thenReturn(Optional.empty());
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "MEAL", "999", "LIKE", null, null, null, null, null)));
        assertEquals(HealthApiException.CODE_NOT_FOUND, error.code());
    }

    @Test
    void resourceId为空允许会话级写入() {
        service.save(1L, new HealthFeedbackRequest("sess-1", null, null, "LIKE", null, null, 4, "整体不错", null));

        verify(feedbackMapper).insertTyped(
                eq(1L), eq("sess-1"), eq(null),
                eq(null),
                eq(null), eq(null),
                eq(null), eq(null),
                eq("LIKE"), eq(4), eq("整体不错"),
                eq("HEALTH_CHAT"));
        verify(provider, never()).mealById(any());
        verify(provider, never()).exerciseById(any());
    }

    @Test
    void 只提供资源类型不提供资源ID拒绝() {
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "MEAL", null, "LIKE", null, null, null, null, null)));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
    }

    @Test
    void 跨用户planId返回归属错误且不写入() {
        when(planMapper.findPlanById(99L, 1L)).thenReturn(null);
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", null, null, "ADOPT", 99L, null, null, null, null)));
        assertEquals(HealthApiException.CODE_NOT_FOUND, error.code());
        assertTrue(error.getMessage().contains("无权访问"));
        verify(feedbackMapper, never()).insertTyped(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 跨用户planItemId返回归属错误() {
        when(planMapper.findPlanById(1L, 1L)).thenReturn(plan(1L, 1L, 2L));
        when(planMapper.findItemById(10L)).thenReturn(item(10L, 2L, 2L, "MEAL", "5"));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", null, null, "ADOPT", 1L, 10L, null, null, null)));
        assertEquals(HealthApiException.CODE_NOT_FOUND, error.code());
        assertEquals("计划项目不存在", error.getMessage());
    }

    @Test
    void planItemId与planId不匹配返回NOT_FOUND() {
        when(planMapper.findPlanById(1L, 1L)).thenReturn(plan(1L, 1L, 2L));
        when(planMapper.findItemById(10L)).thenReturn(item(10L, 7L, 2L, "MEAL", "5"));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", null, null, "ADOPT", 1L, 10L, null, null, null)));
        assertEquals(HealthApiException.CODE_NOT_FOUND, error.code());
    }

    @Test
    void planItemId版本非当前版本返回NOT_FOUND() {
        when(planMapper.findPlanById(1L, 1L)).thenReturn(plan(1L, 1L, 3L));
        when(planMapper.findItemById(10L)).thenReturn(item(10L, 1L, 2L, "MEAL", "5"));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", null, null, "ADOPT", 1L, 10L, null, null, null)));
        assertEquals(HealthApiException.CODE_NOT_FOUND, error.code());
    }

    @Test
    void planItemId未提供planId拒绝() {
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", null, null, "ADOPT", null, 10L, null, null, null)));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
    }

    @Test
    void 资源与计划项目不匹配时明确报错() {
        when(planMapper.findPlanById(1L, 1L)).thenReturn(plan(1L, 1L, 2L));
        when(planMapper.findItemById(10L)).thenReturn(item(10L, 1L, 2L, "MEAL", "5"));
        when(provider.exerciseById("9001")).thenReturn(Optional.of(EXERCISE_9001));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "EXERCISE", "9001", "ADOPT", 1L, 10L, null, null, null)));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("不匹配"));
    }

    @Test
    void planItemId派生资源类型ROUTINE拒绝() {
        when(planMapper.findPlanById(1L, 1L)).thenReturn(plan(1L, 1L, 2L));
        when(planMapper.findItemById(10L)).thenReturn(item(10L, 1L, 2L, "ROUTINE", "R1"));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", null, null, "ADOPT", 1L, 10L, null, null, null)));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("作息事实"));
    }

    @Test
    void planItemId成功写入并派生资源字段() {
        when(planMapper.findPlanById(1L, 1L)).thenReturn(plan(1L, 1L, 2L));
        when(planMapper.findItemById(10L)).thenReturn(item(10L, 1L, 2L, "MEAL", "5"));
        service.save(1L, new HealthFeedbackRequest("sess-1", null, null, "ADOPT", 1L, 10L, null, "计划采纳", null));

        verify(feedbackMapper).insertTyped(
                eq(1L), eq("sess-1"), eq(null),
                eq(null),
                eq("MEAL"), eq("5"),
                eq(1L), eq(10L),
                eq("ADOPT"), eq(null), eq("计划采纳"),
                eq("HEALTH_CHAT"));
    }

    // ---------- #74 traceId 精确归因校验 ----------

    @Test
    void traceId不存在时返回无权访问且不写入() {
        when(traceService.findByTraceId(1L, "trace-ghost")).thenReturn(null);
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "MEAL", "5", "LIKE", null, null, null, null, null, "trace-ghost")));
        assertEquals(HealthApiException.CODE_NOT_FOUND, error.code());
        assertTrue(error.getMessage().contains("无权访问"), "Trace 不存在必须报无权访问类错误: " + error.getMessage());
        verify(feedbackMapper, never()).insertTyped(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void traceId与请求sessionId不匹配时返回无权访问且不写入() throws Exception {
        when(traceService.findByTraceId(1L, "trace-1")).thenReturn(trace("trace-1", "sess-other", responseJson("sess-other", List.of(block("MEAL", "5")))));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "MEAL", "5", "LIKE", null, null, null, null, null, "trace-1")));
        assertEquals(HealthApiException.CODE_NOT_FOUND, error.code());
        assertTrue(error.getMessage().contains("无权访问"));
        verify(feedbackMapper, never()).insertTyped(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void traceId反馈未提供资源时返回400且不写入() throws Exception {
        when(traceService.findByTraceId(1L, "trace-1")).thenReturn(trace("trace-1", "sess-1", responseJson("sess-1", List.of(block("MEAL", "5")))));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", null, null, "LIKE", null, null, null, null, null, "trace-1")));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("traceId"));
        verify(feedbackMapper, never()).insertTyped(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void traceId反馈资源不在该trace推荐结果中时返回400且不写入() throws Exception {
        // 该 trace 只推荐了 MEAL:5，对 MEAL:999 反馈必须拒绝（且不落到资源库存在性校验）。
        when(traceService.findByTraceId(1L, "trace-1")).thenReturn(trace("trace-1", "sess-1", responseJson("sess-1", List.of(block("MEAL", "5")))));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "MEAL", "999", "LIKE", null, null, null, null, null, "trace-1")));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("推荐结果"), "文案应说明资源不在推荐结果中: " + error.getMessage());
        verify(feedbackMapper, never()).insertTyped(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(provider, never()).mealById(any());
    }

    @Test
    void 同一会话其他轮次出现的资源不能通过校验() throws Exception {
        // 资源 MEAL:9 出现在同会话另一 trace 的推荐里，但目标 trace-1 只推荐 MEAL:5 → 必须 400。
        when(traceService.findByTraceId(1L, "trace-1")).thenReturn(trace("trace-1", "sess-1", responseJson("sess-1", List.of(block("MEAL", "5")))));
        when(traceService.findByTraceId(1L, "trace-2")).thenReturn(trace("trace-2", "sess-1", responseJson("sess-1", List.of(block("MEAL", "9")))));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "MEAL", "9", "LIKE", null, null, null, null, null, "trace-1")));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        verify(feedbackMapper, never()).insertTyped(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void traceId反馈response_json为空时返回400且不写入() {
        when(traceService.findByTraceId(1L, "trace-1")).thenReturn(trace("trace-1", "sess-1", null));
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.save(1L,
                new HealthFeedbackRequest("sess-1", "MEAL", "5", "LIKE", null, null, null, null, null, "trace-1")));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        verify(feedbackMapper, never()).insertTyped(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 合法traceId反馈成功写入并携带traceId() throws Exception {
        when(traceService.findByTraceId(1L, "trace-1")).thenReturn(trace("trace-1", "sess-1",
                responseJson("sess-1", List.of(block("MEAL", "5"), block("EXERCISE", "9001")))));
        service.save(1L, new HealthFeedbackRequest("sess-1", "EXERCISE", "9001", "FAVORITE",
                null, null, null, null, null, "trace-1"));

        verify(feedbackMapper).insertTyped(
                eq(1L), eq("sess-1"), eq("trace-1"),
                eq(null),
                eq("EXERCISE"), eq("9001"),
                eq(null), eq(null),
                eq("FAVORITE"), eq(null), eq(null),
                eq("HEALTH_CHAT"));
    }
}
