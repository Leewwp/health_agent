package com.diet.service.feedback;

import com.diet.exception.DietException;
import com.diet.health.feedback.FeedbackConstants;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRequest;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {
    private final FeedbackMapper feedbackMapper;

    public FeedbackService(FeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    /** 旧饮食接口适配层：itemId → MEAL 类型化字段 + LEGACY_DIET 来源，保留原字段。 */
    public void save(Long userId, FeedbackRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new DietException("反馈 sessionId 不能为空");
        }
        if (request.action() == null || request.action().isBlank()) {
            throw new DietException("反馈 action 不能为空");
        }
        feedbackMapper.insertTyped(
                userId,
                request.sessionId(),
                request.itemId(),
                FeedbackConstants.RESOURCE_TYPE_MEAL,
                request.itemId() == null ? null : String.valueOf(request.itemId()),
                null,
                null,
                request.action(),
                request.rating(),
                request.reason(),
                FeedbackConstants.SOURCE_LEGACY_DIET
        );
    }
}
