package com.diet.service.feedback;

import com.diet.exception.DietException;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 旧饮食反馈适配层（41 号票 + #74）：
 * itemId → MEAL 类型化字段 + LEGACY_DIET 来源写入，原字段保留，旧接口行为不变；
 * traceId 恒为 NULL（旧链路不伪造精确归因）。
 */
class FeedbackServiceTest {

    private final FeedbackMapper mapper = mock(FeedbackMapper.class);
    private final FeedbackService service = new FeedbackService(mapper);

    @Test
    void 旧接口写入类型化字段并保留原字段() {
        FeedbackRequest request = new FeedbackRequest()
                .sessionId("sess-1")
                .itemId(3L)
                .action("LIKE")
                .rating(5)
                .reason("口味合适");
        service.save(1L, request);

        verify(mapper).insertTyped(
                eq(1L), eq("sess-1"), eq(null),
                eq(3L),
                eq("MEAL"), eq("3"),
                eq(null), eq(null),
                eq("LIKE"), eq(5), eq("口味合适"),
                eq("LEGACY_DIET"));
    }

    @Test
    void 旧接口itemId为空时资源标识为空且来源仍为LEGACY_DIET() {
        FeedbackRequest request = new FeedbackRequest()
                .sessionId("sess-1")
                .action("DISLIKE");
        service.save(1L, request);

        verify(mapper).insertTyped(
                eq(1L), eq("sess-1"), eq(null),
                eq(null),
                eq("MEAL"), eq(null),
                eq(null), eq(null),
                eq("DISLIKE"), eq(null), eq(null),
                eq("LEGACY_DIET"));
    }

    @Test
    void 旧接口traceId恒为NULL不伪造() {
        FeedbackRequest request = new FeedbackRequest()
                .sessionId("sess-1")
                .itemId(3L)
                .action("LIKE");
        service.save(1L, request);

        // #74：旧饮食链路不得携带任何 traceId，保持 NULL 归因语义。
        verify(mapper).insertTyped(
                eq(1L), eq("sess-1"), eq(null),
                any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void 旧接口sessionId为空拒绝且不写入() {
        FeedbackRequest request = new FeedbackRequest().sessionId("  ").action("LIKE");
        assertThrows(DietException.class, () -> service.save(1L, request));
        verify(mapper, never()).insertTyped(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 旧接口action为空拒绝() {
        FeedbackRequest request = new FeedbackRequest().sessionId("sess-1").action(null);
        assertThrows(DietException.class, () -> service.save(1L, request));
    }
}
