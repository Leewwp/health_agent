package com.diet.service.evaluation;

import com.diet.mapper.FeedbackMapper;
import com.diet.model.EvaluationReport;
import com.diet.model.EvaluationRequest;
import com.diet.model.FeedbackRow;
import com.diet.model.RequestTraceRow;
import com.diet.model.TraceEvaluationResult;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评估器反馈归因（#74）：
 * 先按 trace_id 精确归因（EXACT_TRACE），trace 为空的旧反馈才走 session/时间窗口回退
 * （LEGACY_SESSION_FALLBACK）；报告区分两种计数，明细携带 feedbackAttribution 标记，
 * 有 traceId 但无匹配反馈的 trace 不得把同 session 旧反馈伪装成精确命中。
 */
class EvaluationServiceTest {

    private static final long USER = 1L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 31, 0, 0);

    private final AgentTraceService traceService = mock(AgentTraceService.class);
    private final FeedbackMapper feedbackMapper = mock(FeedbackMapper.class);
    private final EvaluationJudgeService judgeService = mock(EvaluationJudgeService.class);
    private final EvaluationService service =
            new EvaluationService(traceService, feedbackMapper, new ObjectMapper(), judgeService);

    @BeforeEach
    void setUp() {
        when(traceService.findByTimeRange(any(Long.class), any(LocalDateTime.class), any(LocalDateTime.class),
                anyBoolean(), anyInt())).thenReturn(List.of());
    }

    private RequestTraceRow trace(Long id, String traceId, String sessionId) {
        RequestTraceRow row = new RequestTraceRow();
        row.setId(id);
        row.setTraceId(traceId);
        row.setSessionId(sessionId);
        row.setUserId(USER);
        row.setCreatedAt(LocalDateTime.of(2026, 8, 10, 12, 0));
        return row;
    }

    private FeedbackRow feedback(Long id, String sessionId, String traceId) {
        FeedbackRow row = new FeedbackRow();
        row.setId(id);
        row.setUserId(USER);
        row.setSessionId(sessionId);
        row.setTraceId(traceId);
        row.setAction("LIKE");
        row.setSource("HEALTH_CHAT");
        return row;
    }

    private EvaluationRequest request() {
        return new EvaluationRequest(START, END, false, 100);
    }

    private Map<String, Object> detailFor(EvaluationReport report, String traceId) {
        return report.traceResults().stream()
                .filter(result -> java.util.Objects.equals(traceId, result.traceId()))
                .findFirst()
                .orElseThrow()
                .detail();
    }

    /** 按结果顺序取第 index 条 trace 的明细（旧 trace 的 traceId 为 null，需按位置定位）。 */
    private Map<String, Object> detailAt(EvaluationReport report, int index) {
        return report.traceResults().get(index).detail();
    }

    @Test
    void traceId精确归因计数与明细标记() {
        RequestTraceRow matched = trace(1L, "trace-a", "sess-1");
        RequestTraceRow unmatched = trace(2L, "trace-b", "sess-2");
        when(traceService.findByTimeRange(any(Long.class), any(LocalDateTime.class), any(LocalDateTime.class),
                anyBoolean(), anyInt())).thenReturn(List.of(matched, unmatched));
        when(feedbackMapper.findByTraceIds(USER, List.of("trace-a", "trace-b")))
                .thenReturn(List.of(feedback(1L, "sess-1", "trace-a")));

        EvaluationReport report = service.evaluate(USER, request());

        assertEquals(1, report.exactAttributionCount(), "只有命中 trace_id 的 trace 计入精确归因");
        assertEquals(0, report.legacyFallbackCount());
        assertEquals("EXACT_TRACE", detailFor(report, "trace-a").get("feedbackAttribution"));
        assertNull(detailFor(report, "trace-b").get("feedbackAttribution"),
                "有 traceId 但无匹配反馈的 trace 标记必须为 null，不得回退伪装");
        // 无匹配的 trace-b 不得用同 session 旧反馈冒充精确命中。
        verify(feedbackMapper, never()).findBySessions(any(), any(), any(), any());
    }

    @Test
    void 旧trace走session回退归因计数与明细标记() {
        RequestTraceRow legacy1 = trace(10L, null, "sess-old-1");
        RequestTraceRow legacy2 = trace(11L, null, "sess-old-2");
        when(traceService.findByTimeRange(any(Long.class), any(LocalDateTime.class), any(LocalDateTime.class),
                anyBoolean(), anyInt())).thenReturn(List.of(legacy1, legacy2));
        when(feedbackMapper.findByTraceIds(eq(USER), any())).thenReturn(List.of());
        when(feedbackMapper.findBySessions(USER, List.of("sess-old-1", "sess-old-2"), START, END))
                .thenReturn(List.of(feedback(2L, "sess-old-1", null)));

        EvaluationReport report = service.evaluate(USER, request());

        assertEquals(0, report.exactAttributionCount());
        assertEquals(1, report.legacyFallbackCount(), "只有命中 session 回退的旧 trace 计入回退归因");
        assertEquals("LEGACY_SESSION_FALLBACK", detailAt(report, 0).get("feedbackAttribution"),
                "sess-old-1 命中旧反馈，标记为回退归因");
        assertNull(detailAt(report, 1).get("feedbackAttribution"),
                "sess-old-2 无旧反馈，标记为 null");
    }

    @Test
    void 混合归因时两种计数同时累计且查询分离() {
        RequestTraceRow exact = trace(1L, "trace-x", "sess-x");
        RequestTraceRow legacy = trace(20L, null, "sess-y");
        RequestTraceRow silent = trace(3L, "trace-z", "sess-z");
        when(traceService.findByTimeRange(any(Long.class), any(LocalDateTime.class), any(LocalDateTime.class),
                anyBoolean(), anyInt())).thenReturn(List.of(exact, legacy, silent));
        when(feedbackMapper.findByTraceIds(USER, List.of("trace-x", "trace-z")))
                .thenReturn(List.of(feedback(1L, "sess-x", "trace-x")));
        when(feedbackMapper.findBySessions(USER, List.of("sess-y"), START, END))
                .thenReturn(List.of(feedback(2L, "sess-y", null)));

        EvaluationReport report = service.evaluate(USER, request());

        assertEquals(1, report.exactAttributionCount());
        assertEquals(1, report.legacyFallbackCount());
        assertEquals(1, report.totalTraces() - report.exactAttributionCount() - report.legacyFallbackCount(),
                "无反馈 trace（trace-z）不计入任何归因计数");
        // 精确查询只收有 traceId 的 trace，回退查询只收旧 trace 的 session。
        verify(feedbackMapper).findByTraceIds(USER, List.of("trace-x", "trace-z"));
        verify(feedbackMapper).findBySessions(USER, List.of("sess-y"), START, END);
    }

    @Test
    void 精确反馈不影响用户反馈分计算() {
        RequestTraceRow exact = trace(1L, "trace-x", "sess-x");
        when(traceService.findByTimeRange(any(Long.class), any(LocalDateTime.class), any(LocalDateTime.class),
                anyBoolean(), anyInt())).thenReturn(List.of(exact));
        FeedbackRow like = feedback(1L, "sess-x", "trace-x");
        like.setAction("LIKE");
        when(feedbackMapper.findByTraceIds(USER, List.of("trace-x"))).thenReturn(List.of(like));

        EvaluationReport report = service.evaluate(USER, request());
        TraceEvaluationResult result = report.traceResults().get(0);

        assertEquals(100.0, result.userFeedbackScore(), "LIKE 反馈归一化后应为满分");
        assertEquals("EXACT_TRACE", result.detail().get("feedbackAttribution"));
    }

    @Test
    void 无反馈trace不产生归因计数() {
        RequestTraceRow exact = trace(1L, "trace-x", "sess-x");
        when(traceService.findByTimeRange(any(Long.class), any(LocalDateTime.class), any(LocalDateTime.class),
                anyBoolean(), anyInt())).thenReturn(List.of(exact));
        when(feedbackMapper.findByTraceIds(USER, List.of("trace-x"))).thenReturn(List.of());

        EvaluationReport report = service.evaluate(USER, request());

        assertEquals(0, report.exactAttributionCount());
        assertEquals(0, report.legacyFallbackCount());
        assertNull(detailFor(report, "trace-x").get("feedbackAttribution"));
        assertNull(report.traceResults().get(0).userFeedbackScore(), "无反馈时反馈分为 null");
    }
}
