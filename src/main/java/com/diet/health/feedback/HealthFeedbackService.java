package com.diet.health.feedback;

import com.diet.exception.HealthApiException;
import com.diet.health.model.HealthFeedbackRequest;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.mapper.FeedbackMapper;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 健康反馈服务（41 号票 + #65）：
 * action 白名单（LIKE/DISLIKE/FAVORITE/UNFAVORITE/ADOPT）；资源类型白名单 MEAL/EXERCISE，
 * 作息事实不参与偏好；资源存在性经 HealthResourceProvider 校验；planId/planItemId
 * 参照周计划 PATCH 的归属 + 当前版本校验，跨用户与资源不匹配一律明确报错且不写入。
 */
@Service
public class HealthFeedbackService {

    private static final Set<String> ACTIONS = Set.of(
            FeedbackConstants.ACTION_LIKE,
            FeedbackConstants.ACTION_DISLIKE,
            FeedbackConstants.ACTION_FAVORITE,
            FeedbackConstants.ACTION_UNFAVORITE,
            FeedbackConstants.ACTION_ADOPT
    );
    private static final Set<String> RESOURCE_TYPES = Set.of(
            FeedbackConstants.RESOURCE_TYPE_MEAL,
            FeedbackConstants.RESOURCE_TYPE_EXERCISE
    );

    private final FeedbackMapper feedbackMapper;
    private final WeeklyPlanMapper planMapper;
    private final HealthResourceProvider resourceProvider;

    public HealthFeedbackService(FeedbackMapper feedbackMapper, WeeklyPlanMapper planMapper,
                                 HealthResourceProvider resourceProvider) {
        this.feedbackMapper = feedbackMapper;
        this.planMapper = planMapper;
        this.resourceProvider = resourceProvider;
    }

    public void save(Long userId, HealthFeedbackRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "反馈 sessionId 不能为空");
        }
        String action = request.action();
        if (action == null || !ACTIONS.contains(action)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST,
                    "反馈 action 必须为 LIKE/DISLIKE/FAVORITE/UNFAVORITE/ADOPT");
        }
        String resourceType = normalize(request.resourceType());
        String resourceId = normalize(request.resourceId());
        if (FeedbackConstants.RESOURCE_TYPE_ROUTINE.equals(resourceType)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "作息事实不参与偏好反馈");
        }
        if (resourceType != null && !RESOURCE_TYPES.contains(resourceType)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "资源类型必须为 MEAL 或 EXERCISE");
        }
        if ((resourceType == null) != (resourceId == null)) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "资源类型与资源 ID 需同时提供或同时省略");
        }
        if (resourceType != null && !exists(resourceType, resourceId)) {
            throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "反馈资源不存在");
        }

        WeeklyPlanRow plan = null;
        if (request.planId() != null) {
            plan = planMapper.findPlanById(request.planId(), userId);
            if (plan == null) {
                throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "计划不存在或无权访问");
            }
        }
        WeeklyPlanItemRow item = null;
        if (request.planItemId() != null) {
            if (plan == null) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "planItemId 需要同时提供 planId");
            }
            item = planMapper.findItemById(request.planItemId());
            if (item == null || !plan.getId().equals(item.getPlanId())
                    || !plan.getCurrentVersion().equals(item.getVersionNo())) {
                throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "计划项目不存在");
            }
            if (resourceType != null && !item.getResourceType().equals(resourceType)) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划项目与反馈资源类型不匹配");
            }
            if (resourceId != null && !item.getResourceId().equals(resourceId)) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划项目与反馈资源标识不匹配");
            }
            resourceType = item.getResourceType();
            resourceId = item.getResourceId();
            if (FeedbackConstants.RESOURCE_TYPE_ROUTINE.equals(resourceType)) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "作息事实不参与偏好反馈");
            }
        }

        String source = normalize(request.source());
        if (source == null) {
            source = FeedbackConstants.SOURCE_HEALTH_CHAT;
        }
        feedbackMapper.insertTyped(userId, request.sessionId(), null, resourceType, resourceId,
                plan == null ? null : plan.getId(), item == null ? null : item.getId(),
                action, request.rating(), request.reason(), source);
    }

    private boolean exists(String resourceType, String resourceId) {
        if (FeedbackConstants.RESOURCE_TYPE_MEAL.equals(resourceType)) {
            return resourceProvider.mealById(resourceId).isPresent();
        }
        return resourceProvider.exerciseById(resourceId).isPresent();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
