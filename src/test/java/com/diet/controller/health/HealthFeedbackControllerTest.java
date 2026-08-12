package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.exception.HealthApiExceptionHandler;
import com.diet.health.feedback.HealthFeedbackService;
import com.diet.health.model.HealthFeedbackRequest;
import com.diet.health.module.HealthResource;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.FeedbackMapper;
import com.diet.mapper.WeeklyPlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
 * 健康反馈 HTTP 入口测试（41 号票 + #65）：
 * 五种合法 action（含 UNFAVORITE）经真实服务持久化；非法 action 在 HTTP 层返回 400
 * 且错误文案列出五种 action；资源不存在返回 404。
 */
class HealthFeedbackControllerTest {

    private static final long USER = 100L;

    private final FeedbackMapper feedbackMapper = mock(FeedbackMapper.class);
    private final WeeklyPlanMapper planMapper = mock(WeeklyPlanMapper.class);
    private final HealthResourceProvider provider = mock(HealthResourceProvider.class);
    private MockMvc mockMvc;

    private static final HealthResource MEAL_5 = new HealthResource(
            "MEAL", "5", "清蒸鲈鱼", "PUBLIC", "公共餐食库", null, false, java.util.Map.of());

    @BeforeEach
    void setUp() {
        HealthFeedbackService service = new HealthFeedbackService(feedbackMapper, planMapper, provider);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HealthFeedbackController(service))
                .setControllerAdvice(new HealthApiExceptionHandler())
                .build();
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 非法action返回400且文案列出五种action() throws Exception {
        when(provider.mealById("5")).thenReturn(Optional.of(MEAL_5));
        postFeedback("THUMBS_UP", "5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("反馈 action 必须为 LIKE/DISLIKE/FAVORITE/UNFAVORITE/ADOPT"));
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
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
