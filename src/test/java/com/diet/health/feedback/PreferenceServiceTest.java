package com.diet.health.feedback;

import com.diet.constants.DietConstants;
import com.diet.health.module.HealthResource;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 偏好消费服务（17 号票）：
 * DISLIKE 硬过滤、LIKE/FAVORITE/ADOPT 稳定前移、同一资源 DISLIKE 优先、
 * 会话级与非法类型不参与、最近 100 条截断、mapper 异常确定性降级。
 */
class PreferenceServiceTest {

    private final FeedbackMapper mapper = mock(FeedbackMapper.class);
    private final PreferenceService service = new PreferenceService(mapper);

    private FeedbackRow feedback(String action, String resourceType, String resourceId) {
        FeedbackRow row = new FeedbackRow();
        row.setAction(action);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        return row;
    }

    private HealthResource resource(String type, String id) {
        return new HealthResource(type, id, "name-" + id, "PUBLIC", "公共餐食库", null, false, Map.of());
    }

    private List<String> keys(List<HealthResource> resources) {
        return resources.stream().map(item -> item.resourceType() + ":" + item.resourceId()).toList();
    }

    @Test
    void DISLIKE硬过滤且LIKE与FAVORITE稳定前移() {
        when(mapper.findRecent(1L, 100)).thenReturn(List.of(
                feedback("DISLIKE", "MEAL", "3"),
                feedback("LIKE", "MEAL", "5"),
                feedback("FAVORITE", "EXERCISE", "9001")));
        PreferenceService.PreferenceView view = service.preferencesFor(1L);

        assertTrue(view.excludedKeys().contains("MEAL:3"));
        assertTrue(view.boostedKeys().contains("MEAL:5"));
        assertTrue(view.boostedKeys().contains("EXERCISE:9001"));

        List<HealthResource> result = service.reorder(List.of(
                resource("MEAL", "3"), resource("MEAL", "7"), resource("MEAL", "5"),
                resource("EXERCISE", "9002"), resource("EXERCISE", "9001")), view);
        assertEquals(List.of("MEAL:5", "EXERCISE:9001", "MEAL:7", "EXERCISE:9002"), keys(result),
                "DISLIKE 被过滤，LIKE/FAVORITE 移到前部且保持原相对顺序");
    }

    @Test
    void 同一资源DISLIKE优先于LIKE() {
        when(mapper.findRecent(1L, 100)).thenReturn(List.of(
                feedback("LIKE", "MEAL", "5"),
                feedback("DISLIKE", "MEAL", "5")));
        PreferenceService.PreferenceView view = service.preferencesFor(1L);

        assertTrue(view.excludedKeys().contains("MEAL:5"));
        assertFalse(view.boostedKeys().contains("MEAL:5"));
    }

    @Test
    void 会话级与非法类型反馈不参与聚合() {
        when(mapper.findRecent(1L, 100)).thenReturn(List.of(
                feedback("LIKE", "MEAL", null),
                feedback("FAVORITE", "ROUTINE", "R1"),
                feedback("DISLIKE", "MEAL", "2")));
        PreferenceService.PreferenceView view = service.preferencesFor(1L);

        assertTrue(view.excludedKeys().contains("MEAL:2"));
        assertEquals(1, view.excludedKeys().size(), "会话级与 ROUTINE 反馈不得进入偏好集合");
        assertTrue(view.boostedKeys().isEmpty());
    }

    @Test
    void mapper异常确定性降级为空集合() {
        when(mapper.findRecent(any(), anyInt())).thenThrow(new RuntimeException("数据库不可用"));
        PreferenceService.PreferenceView view = service.preferencesFor(1L);
        assertTrue(view.isEmpty(), "读取失败必须降级为空集合而不是抛错");
    }

    @Test
    void 聚合读取最近100条反馈() {
        when(mapper.findRecent(1L, 100)).thenReturn(List.of());
        service.preferencesFor(1L);
        verify(mapper).findRecent(eq(1L), eq(100));
    }

    @Test
    void 无Web请求上下文时偏好消费原样返回() {
        List<HealthResource> candidates = List.of(resource("MEAL", "5"));
        assertEquals(candidates, service.applyPreference(candidates), "无请求上下文时确定性透传");
    }

    @Test
    void 有Web请求上下文时应用偏好() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DietConstants.USER_ID_ATTRIBUTE, 1L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(mapper.findRecent(1L, 100)).thenReturn(List.of(feedback("DISLIKE", "MEAL", "3")));
            List<HealthResource> result = service.applyPreference(
                    List.of(resource("MEAL", "3"), resource("MEAL", "5")));
            assertEquals(List.of("MEAL:5"), keys(result));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
