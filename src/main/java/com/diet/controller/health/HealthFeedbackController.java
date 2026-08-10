package com.diet.controller.health;

import com.diet.constants.DietConstants;
import com.diet.health.feedback.HealthFeedbackService;
import com.diet.health.model.HealthFeedbackRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康类型化反馈 HTTP 入口（41 号票）。
 * 身份由匿名 Cookie 拦截器解析；校验失败经 HealthApiExceptionHandler 返回统一错误结构。
 */
@RestController
@RequestMapping("/api/v1/health/feedback")
public class HealthFeedbackController {

    private final HealthFeedbackService feedbackService;

    public HealthFeedbackController(HealthFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public void save(
            @RequestAttribute(DietConstants.USER_ID_ATTRIBUTE) Long userId,
            @RequestBody HealthFeedbackRequest request
    ) {
        feedbackService.save(userId, request);
    }
}
