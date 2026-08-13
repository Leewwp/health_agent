package com.diet.health.evalv2;

import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRow;
import com.diet.model.RequestTraceRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 旧 trace 无 trace_id 时，归因结果必须按数据库行隔离，不能互相覆盖。 */
class AuditFeedbackLoaderTest {

    @Test
    void 同一会话的多条旧trace使用不同归因键且各自可读取() {
        FeedbackMapper mapper = mock(FeedbackMapper.class);
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime end = start.plusDays(1);
        RequestTraceRow first = trace(101L);
        RequestTraceRow second = trace(102L);
        when(mapper.findBySessions(1L, List.of("legacy-session"), start, end))
                .thenReturn(List.of());

        Map<String, AuditFeedbackLoader.Attribution> result = new AuditFeedbackLoader(mapper)
                .load(1L, start, end, List.of(first, second));

        String firstKey = AuditFeedbackLoader.attributionKey(first);
        String secondKey = AuditFeedbackLoader.attributionKey(second);
        assertNotEquals(firstKey, secondKey);
        assertEquals(2, result.size());
        assertNotNull(result.get(firstKey));
        assertNotNull(result.get(secondKey));
    }

    @Test
    void 旧trace的会话回退归因逐条标记为LEGACY_SESSION_FALLBACK() {
        FeedbackMapper mapper = mock(FeedbackMapper.class);
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime end = start.plusDays(1);
        RequestTraceRow first = trace(101L);
        RequestTraceRow second = trace(102L);
        FeedbackRow feedback = new FeedbackRow();
        feedback.setSessionId("legacy-session");
        when(mapper.findBySessions(1L, List.of("legacy-session"), start, end))
                .thenReturn(List.of(feedback));

        Map<String, AuditFeedbackLoader.Attribution> result = new AuditFeedbackLoader(mapper)
                .load(1L, start, end, List.of(first, second));

        AuditFeedbackLoader.Attribution firstAttr = result.get(AuditFeedbackLoader.attributionKey(first));
        AuditFeedbackLoader.Attribution secondAttr = result.get(AuditFeedbackLoader.attributionKey(second));
        assertNotNull(firstAttr);
        assertNotNull(secondAttr);
        assertEquals(TraceFactReader.ATTRIBUTION_LEGACY_SESSION_FALLBACK, firstAttr.attribution());
        assertEquals(TraceFactReader.ATTRIBUTION_LEGACY_SESSION_FALLBACK, secondAttr.attribution());
        assertEquals(List.of(feedback), firstAttr.feedbacks());
        assertEquals(List.of(feedback), secondAttr.feedbacks());
    }

    @Test
    void traceId与行id皆缺时归因键快速失败() {
        RequestTraceRow row = new RequestTraceRow();
        row.setSessionId("legacy-session");
        assertThrows(IllegalStateException.class, () -> AuditFeedbackLoader.attributionKey(row));
    }

    private RequestTraceRow trace(Long id) {
        RequestTraceRow row = new RequestTraceRow();
        row.setId(id);
        row.setSessionId("legacy-session");
        return row;
    }
}
